/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.mycat.calcite.*;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.physical.MycatInsertRel;
import io.mycat.calcite.physical.MycatUpdateRel;
import io.mycat.calcite.rewriter.*;
import io.mycat.calcite.rules.MycatViewToIndexViewRule;
import io.mycat.calcite.spm.Plan;
import io.mycat.calcite.spm.PlanImpl;
import io.mycat.calcite.table.*;
import io.mycat.gsi.GSIService;
import io.mycat.hbt.HBTQueryConvertor;
import io.mycat.hbt.SchemaConvertor;
import io.mycat.hbt.ast.base.Schema;
import io.mycat.hbt.parser.HBTParser;
import io.mycat.hbt.parser.ParseNode;
import io.mycat.router.CustomRuleFunction;
import io.mycat.router.ShardingTableHandler;
import lombok.SneakyThrows;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.*;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.RelBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static io.mycat.DrdsExecutorCompiler.getCodeExecuterContext;
import static org.apache.calcite.rel.rules.CoreRules.JOIN_PUSH_TRANSITIVE_PREDICATES;

public class DrdsSqlCompiler {
    private final static Logger log = LoggerFactory.getLogger(DrdsSqlCompiler.class);
    private final SchemaPlus schemas;

    public DrdsSqlCompiler(DrdsConst config) {
        this.schemas = DrdsRunnerHelper.convertRoSchemaPlus(config);
    }

    @SneakyThrows
    public Plan doHbt(String hbtText) {
        log.debug("reveice hbt");
        log.debug(hbtText);
        HBTParser hbtParser = new HBTParser(hbtText);
        ParseNode statement = hbtParser.statement();
        SchemaConvertor schemaConvertor = new SchemaConvertor();
        Schema originSchema = schemaConvertor.transforSchema(statement);
        SchemaPlus plus = this.schemas;
        CalciteCatalogReader catalogReader = new CalciteCatalogReader(CalciteSchema
                .from(plus),
                ImmutableList.of(),
                MycatCalciteSupport.TypeFactory,
                MycatCalciteSupport.INSTANCE.getCalciteConnectionConfig());
        RelOptCluster cluster = newCluster();
        RelBuilder relBuilder = MycatCalciteSupport.relBuilderFactory.create(cluster, catalogReader);
        HBTQueryConvertor hbtQueryConvertor = new HBTQueryConvertor(Collections.emptyList(), relBuilder);
        RelNode relNode = hbtQueryConvertor.complie(originSchema);
        HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();
        hepProgramBuilder.addRuleInstance(CoreRules.AGGREGATE_REDUCE_FUNCTIONS);
        hepProgramBuilder.addMatchLimit(512);
        HepProgram hepProgram = hepProgramBuilder.build();
        HepPlanner hepPlanner = new HepPlanner(hepProgram);
        hepPlanner.setRoot(relNode);
        RelNode bestExp = hepPlanner.findBestExp();
        bestExp = bestExp.accept(new RelShuttleImpl() {
            @Override
            public RelNode visit(TableScan scan) {
                AbstractMycatTable table = scan.getTable().unwrap(AbstractMycatTable.class);
                if (table != null) {
                    if (table instanceof MycatPhysicalTable) {
                        DataNode dataNode = ((MycatPhysicalTable) table).getDataNode();
                        MycatPhysicalTable mycatPhysicalTable = (MycatPhysicalTable) table;
                        SqlNode sqlNode = MycatCalciteSupport.INSTANCE.convertToSqlTemplate(
                                scan,
                                MycatCalciteSupport.INSTANCE.getSqlDialectByTargetName(dataNode.getTargetName()),
                                false
                        );
                        SqlDialect dialect = MycatCalciteSupport.INSTANCE.getSqlDialectByTargetName(dataNode.getTargetName());
                        return new MycatTransientSQLTableScan(cluster,
                                mycatPhysicalTable.getRowType(),
                                dataNode.getTargetName(), sqlNode.toSqlString(dialect)
                               .getSql());
                    }
                }
                return super.visit(scan);
            }
        });
        MycatRel mycatRel = optimizeWithCBO(bestExp, Collections.emptyList());
        CodeExecuterContext codeExecuterContext = getCodeExecuterContext(mycatRel, false);
        return new PlanImpl(mycatRel, codeExecuterContext, mycatRel.getRowType().getFieldNames());
    }
    public MycatRel dispatch(OptimizationContext optimizationContext,
                             DrdsSql drdsSql){
       return dispatch(optimizationContext,drdsSql,schemas);
    }

    public MycatRel dispatch(OptimizationContext optimizationContext,
                             DrdsSql drdsSql,
                             SchemaPlus plus) {
        SQLStatement sqlStatement = drdsSql.getParameterizedStatement();
        if (sqlStatement instanceof SQLSelectStatement) {
            return compileQuery(optimizationContext, plus, drdsSql);
        }
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        metadataManager.resolveMetadata(sqlStatement);
        if (sqlStatement instanceof MySqlInsertStatement) {
            MySqlInsertStatement insertStatement = (MySqlInsertStatement) sqlStatement;
            String schemaName = SQLUtils.normalize(insertStatement.getTableSource().getSchema());
            String tableName = SQLUtils.normalize(insertStatement.getTableName().getSimpleName());
            TableHandler logicTable = Objects.requireNonNull(metadataManager.getTable(schemaName, tableName));
            switch (logicTable.getType()) {
                case SHARDING:
                    return compileInsert((ShardingTable) logicTable, drdsSql, optimizationContext);
                case GLOBAL:
                    return complieGlobalUpdate(optimizationContext, drdsSql, sqlStatement, (GlobalTable) logicTable);
                case NORMAL:
                    return complieNormalUpdate(optimizationContext, drdsSql, sqlStatement, (NormalTable) logicTable);
                case CUSTOM:
                    throw new UnsupportedOperationException();
            }
        } else if (sqlStatement instanceof MySqlUpdateStatement) {
            SQLExprTableSource tableSource = (SQLExprTableSource) ((MySqlUpdateStatement) sqlStatement).getTableSource();
            String schemaName = SQLUtils.normalize(tableSource.getSchema());
            String tableName = SQLUtils.normalize(((MySqlUpdateStatement) sqlStatement).getTableName().getSimpleName());
            TableHandler logicTable = metadataManager.getTable(schemaName, tableName);
            switch (logicTable.getType()) {
                case SHARDING:
                    return compileUpdate(logicTable,optimizationContext, drdsSql, plus);
                case GLOBAL: {
                    return complieGlobalUpdate(optimizationContext, drdsSql, sqlStatement, (GlobalTable) logicTable);
                }
                case NORMAL: {
                    return complieNormalUpdate(optimizationContext, drdsSql, sqlStatement, (NormalTable) logicTable);
                }
                case CUSTOM:
                    throw new UnsupportedOperationException();
            }
        } else if (sqlStatement instanceof MySqlDeleteStatement) {
            SQLExprTableSource tableSource = (SQLExprTableSource) ((MySqlDeleteStatement) sqlStatement).getTableSource();
            String schemaName = SQLUtils.normalize(Optional.ofNullable(tableSource).map(i -> i.getSchema()).orElse(null));
            String tableName = SQLUtils.normalize(((MySqlDeleteStatement) sqlStatement).getTableName().getSimpleName());
            TableHandler logicTable = metadataManager.getTable(schemaName, tableName);
            switch (logicTable.getType()) {
                case SHARDING:
                    return compileDelete(logicTable,optimizationContext, drdsSql, plus);
                case GLOBAL: {
                    return complieGlobalUpdate(optimizationContext, drdsSql, sqlStatement, (GlobalTable) logicTable);
                }
                case NORMAL: {
                    return complieNormalUpdate(optimizationContext, drdsSql, sqlStatement, (NormalTable) logicTable);
                }
                case CUSTOM:
                    throw new UnsupportedOperationException();
            }
        }
        return null;
    }

    @NotNull
    private MycatRel complieGlobalUpdate(OptimizationContext optimizationContext, DrdsSql drdsSql, SQLStatement sqlStatement, GlobalTable logicTable) {
        MycatUpdateRel mycatUpdateRel = new MycatUpdateRel(sqlStatement, logicTable.getSchemaName(), logicTable.getTableName(), IndexCondition.EMPTY);
        optimizationContext.saveAlways();
        return mycatUpdateRel;
    }

    @NotNull
    private MycatRel complieNormalUpdate(OptimizationContext optimizationContext, DrdsSql drdsSql, SQLStatement sqlStatement, NormalTable logicTable) {
        MycatUpdateRel mycatUpdateRel = new MycatUpdateRel(sqlStatement, logicTable.getSchemaName(), logicTable.getTableName(), IndexCondition.EMPTY);
        optimizationContext.saveAlways();
        return mycatUpdateRel;
    }

    private MycatRel compileDelete(TableHandler logicTable, OptimizationContext optimizationContext, DrdsSql drdsSql, SchemaPlus plus) {
        return compileQuery(optimizationContext, plus, drdsSql);
    }

    private MycatRel compileUpdate(TableHandler logicTable,  OptimizationContext optimizationContext, DrdsSql drdsSql, SchemaPlus plus) {
        return compileQuery( optimizationContext, plus, drdsSql);
    }

    private MycatRel compileInsert(ShardingTableHandler logicTable,
                                   DrdsSql drdsSql,
                                   OptimizationContext optimizationContext) {
        MySqlInsertStatement mySqlInsertStatement = drdsSql.getParameterizedStatement();
        List<SQLIdentifierExpr> columnsTmp = (List) mySqlInsertStatement.getColumns();
        boolean autoIncrement = logicTable.isAutoIncrement();
        int autoIncrementIndexTmp = -1;
        ArrayList<Integer> shardingKeys = new ArrayList<>();
        CustomRuleFunction function = logicTable.function();
        List<SimpleColumnInfo> metaColumns;
        if (columnsTmp.isEmpty()) {//fill columns
            int index = 0;
            for (SimpleColumnInfo column : metaColumns = logicTable.getColumns()) {
                if (autoIncrement && logicTable.getAutoIncrementColumn() == column) {
                    autoIncrementIndexTmp = index;
                }
                if (function.isShardingKey(column.getColumnName())) {
                    shardingKeys.add(index);
                }
                mySqlInsertStatement.addColumn(new SQLIdentifierExpr(column.getColumnName()));
                index++;
            }
        } else {
            int index = 0;
            metaColumns = new ArrayList<>();
            for (SQLIdentifierExpr column : columnsTmp) {
                SimpleColumnInfo simpleColumnInfo = logicTable.getColumnByName(SQLUtils.normalize(column.getName()));
                metaColumns.add(simpleColumnInfo);
                if (autoIncrement && logicTable.getAutoIncrementColumn() == simpleColumnInfo) {
                    autoIncrementIndexTmp = index;
                }
                if (function.isShardingKey(simpleColumnInfo.getColumnName())) {
                    shardingKeys.add(index);
                }
                index++;
            }
            if (autoIncrement && autoIncrementIndexTmp == -1) {
                SimpleColumnInfo autoIncrementColumn = logicTable.getAutoIncrementColumn();
                if (function.isShardingKey(autoIncrementColumn.getColumnName())) {
                    shardingKeys.add(index);
                }
                metaColumns.add(autoIncrementColumn);
                mySqlInsertStatement.addColumn(new SQLIdentifierExpr(autoIncrementColumn.getColumnName()));
                class CountIndex extends MySqlASTVisitorAdapter {
                    int currentIndex = -1;

                    @Override
                    public void endVisit(SQLVariantRefExpr x) {
                        currentIndex = Math.max(x.getIndex(), currentIndex);
                        super.endVisit(x);
                    }
                }
                CountIndex countIndex = new CountIndex();
                mySqlInsertStatement.accept(countIndex);
            }
        }
        final int finalAutoIncrementIndex = autoIncrementIndexTmp;
        MycatInsertRel mycatInsertRel = MycatInsertRel.create(finalAutoIncrementIndex,
                shardingKeys,
                mySqlInsertStatement.toString(),
                logicTable.getSchemaName(), logicTable.getTableName());
        optimizationContext.saveParameterized();
        return mycatInsertRel;
    }

    public MycatRel compileQuery(
                                  OptimizationContext optimizationContext,
                                  SchemaPlus plus,
                                  DrdsSql drdsSql) {
        RelNode logPlan;
        RelNodeContext relNodeContext = null;
        {
            relNodeContext = getRelRoot(plus, drdsSql);
            logPlan = relNodeContext.getRoot().project(false);
        }

        if (logPlan instanceof TableModify) {
            LogicalTableModify tableModify = (LogicalTableModify) logPlan;
            switch (tableModify.getOperation()) {
                case DELETE:
                case UPDATE:
                    return planUpdate(tableModify, drdsSql, optimizationContext);
                default:
                    throw new UnsupportedOperationException("unsupported DML operation " + tableModify.getOperation());
            }
        }
        RelNode rboLogPlan = optimizeWithRBO(logPlan);
        if (relNodeContext != null && !(rboLogPlan instanceof MycatView)) {
            RelFieldTrimmer relFieldTrimmer = new MycatRelFieldTrimmer(relNodeContext.getValidator(), relNodeContext.getRelBuilder());
            rboLogPlan = relFieldTrimmer.trim(rboLogPlan);
        }
        Collection<RelOptRule> rboInCbo;
        if (MetaClusterCurrent.exist(GSIService.class)) {
            rboInCbo = Collections.singletonList(
                    new MycatViewToIndexViewRule(optimizationContext)
            );
        } else {
            rboInCbo = Collections.emptyList();
        }
        MycatRel mycatRel = optimizeWithCBO(rboLogPlan, rboInCbo);
        mycatRel = (MycatRel) mycatRel.accept(new MatierialRewriter());
        return mycatRel;
    }



    private RelNodeContext getRelRoot(
                                      SchemaPlus plus, DrdsSql drdsSql) {
        CalciteCatalogReader catalogReader =DrdsRunnerHelper. newCalciteCatalogReader( plus);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, catalogReader);
        RelOptCluster cluster = newCluster();
        SqlToRelConverter sqlToRelConverter = new SqlToRelConverter(
                NOOP_EXPANDER,
                validator,
                catalogReader,
                cluster,
                MycatCalciteSupport.config.getConvertletTable(),
                MycatCalciteSupport.sqlToRelConverterConfig);

        SQLStatement sqlStatement = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor mycatCalciteMySqlNodeVisitor = new MycatCalciteMySqlNodeVisitor();
        sqlStatement.accept(mycatCalciteMySqlNodeVisitor);
        SqlNode sqlNode = mycatCalciteMySqlNodeVisitor.getSqlNode();


        SqlNode validated = validator.validate(sqlNode);
        RelDataType parameterRowType = validator.getParameterRowType(sqlNode);
        RelBuilder relBuilder = MycatCalciteSupport.relBuilderFactory.create(sqlToRelConverter.getCluster(), catalogReader);

        RelRoot root = sqlToRelConverter.convertQuery(validated, false, true);
        RelNode newRelNode = RelDecorrelator.decorrelateQuery(root.rel, relBuilder);

        return new RelNodeContext(root.withRel(newRelNode), sqlToRelConverter, validator, relBuilder,catalogReader,parameterRowType);
    }





    private MycatRel planUpdate(LogicalTableModify tableModify,
                                DrdsSql drdsSql, OptimizationContext optimizationContext) {
        MycatLogicTable mycatTable = (MycatLogicTable) tableModify.getTable().unwrap(AbstractMycatTable.class);
        RelNode input = tableModify.getInput();
        if (input instanceof LogicalProject) {
            input = ((LogicalProject) input).getInput();
        }
        if (input instanceof Filter && ((Filter) input).getInput() instanceof LogicalTableScan) {
            RelDataType rowType = input.getRowType();
            RexNode condition = ((Filter) input).getCondition();
            PredicateAnalyzer predicateAnalyzer = new PredicateAnalyzer((ShardingTable) mycatTable.getTable(), input);
            IndexCondition indexCondition = predicateAnalyzer.translateMatch(condition);
            MycatUpdateRel mycatUpdateRel = MycatUpdateRel.create(
                    drdsSql.getParameterizedStatement(),
                    mycatTable.getTable().getSchemaName(),
                    mycatTable.getTable().getTableName(),
                    indexCondition);
            optimizationContext.saveParameterized();
            return mycatUpdateRel;
        }
        MycatUpdateRel mycatUpdateRel = new MycatUpdateRel(
                drdsSql.getParameterizedStatement(),
                mycatTable.getTable().getSchemaName(),
                mycatTable.getTable().getTableName(),
                false,
                IndexCondition.EMPTY);
        optimizationContext.saveAlways();
        return mycatUpdateRel;
    }


    public MycatRel optimizeWithCBO(RelNode logPlan, Collection<RelOptRule> relOptRules) {
        if (logPlan instanceof MycatRel) {
            return (MycatRel) logPlan;
        } else {
            boolean needJoinReorder = RelOptUtil.countJoins(logPlan) > 1;
            if (needJoinReorder) {
                logPlan = preJoinReorder(logPlan);
            }
            RelOptCluster cluster = logPlan.getCluster();
            RelOptPlanner planner = cluster.getPlanner();
            planner.clear();
            MycatConvention.INSTANCE.register(planner);
            planner.addRule(CoreRules.PROJECT_TO_CALC);
            planner.addRule(CoreRules.FILTER_TO_CALC);
            planner.addRule(CoreRules.CALC_MERGE);

            //joinReorder
            if (needJoinReorder) {
                planner.addRule(CoreRules.MULTI_JOIN_OPTIMIZE);
            }

            if (relOptRules != null) {
                for (RelOptRule relOptRule : relOptRules) {
                    planner.addRule(relOptRule);
                }
            }

            if (log.isDebugEnabled()) {
                MycatRelOptListener mycatRelOptListener = new MycatRelOptListener();
                planner.addListener(mycatRelOptListener);
                log.debug(mycatRelOptListener.dump());
            }
            logPlan = planner.changeTraits(logPlan, cluster.traitSetOf(MycatConvention.INSTANCE));
            planner.setRoot(logPlan);
            RelNode bestExp = planner.findBestExp();

            return (MycatRel) bestExp;
        }
    }

    public static RelNode preJoinReorder(RelNode logPlan) {
        final HepProgram hep = new HepProgramBuilder()
                .addRuleInstance(CoreRules.FILTER_INTO_JOIN)
                .addMatchOrder(HepMatchOrder.BOTTOM_UP)
                .addRuleInstance(CoreRules.JOIN_TO_MULTI_JOIN)
                .addMatchLimit(512)
                .build();
        final HepPlanner hepPlanner = new HepPlanner(hep,
                null, false, null, RelOptCostImpl.FACTORY);
        List<RelMetadataProvider> list = new ArrayList<>();
        list.add(DefaultRelMetadataProvider.INSTANCE);
        hepPlanner.registerMetadataProviders(list);
        hepPlanner.setRoot(logPlan);
        logPlan = hepPlanner.findBestExp();
        return logPlan;
    }

    static final ImmutableSet<RelOptRule> FILTER = ImmutableSet.of(
            JOIN_PUSH_TRANSITIVE_PREDICATES,
            CoreRules.JOIN_SUB_QUERY_TO_CORRELATE,
            CoreRules.FILTER_SUB_QUERY_TO_CORRELATE,
            CoreRules.PROJECT_SUB_QUERY_TO_CORRELATE,
            CoreRules.FILTER_INTO_JOIN,
//            CoreRules.FILTER_INTO_JOIN_DUMB,
            CoreRules.JOIN_CONDITION_PUSH,
            CoreRules.SORT_JOIN_TRANSPOSE,
            CoreRules.FILTER_CORRELATE,
            CoreRules.PROJECT_CORRELATE_TRANSPOSE,
            CoreRules.FILTER_AGGREGATE_TRANSPOSE,
//            CoreRules.FILTER_MULTI_JOIN_MERGE,
            CoreRules.FILTER_PROJECT_TRANSPOSE,
            CoreRules.FILTER_SET_OP_TRANSPOSE,
            CoreRules.FILTER_PROJECT_TRANSPOSE,
//            CoreRules.SEMI_JOIN_FILTER_TRANSPOSE,
            CoreRules.FILTER_REDUCE_EXPRESSIONS,
            CoreRules.JOIN_REDUCE_EXPRESSIONS,
            CoreRules.PROJECT_REDUCE_EXPRESSIONS,
            CoreRules.FILTER_MERGE,
            CoreRules.JOIN_PUSH_EXPRESSIONS,
            CoreRules.JOIN_PUSH_TRANSITIVE_PREDICATES
//            CoreRules.PROJECT_CALC_MERGE,
//            CoreRules.FILTER_CALC_MERGE,
//            CoreRules.FILTER_TO_CALC,
//            CoreRules.PROJECT_TO_CALC,
//            CoreRules.CALC_REMOVE,
//            CoreRules.CALC_MERGE

    );

    private static RelNode optimizeWithRBO(RelNode logPlan) {
        HepProgramBuilder builder = new HepProgramBuilder();
        builder.addMatchLimit(512);
        builder.addRuleCollection(FILTER);
        HepPlanner planner = new HepPlanner(builder.build());
        planner.setRoot(logPlan);
        RelNode bestExp = planner.findBestExp();
        SQLRBORewriter sqlrboRewriter = new SQLRBORewriter();
        return bestExp.accept(sqlrboRewriter);
    }

    @NotNull
    public  CalciteCatalogReader newCalciteCatalogReader() {
   return DrdsRunnerHelper.newCalciteCatalogReader(schemas);
    }
    public static RelOptCluster newCluster() {
        RelOptPlanner planner = new VolcanoPlanner();
        ImmutableList<RelTraitDef> TRAITS = ImmutableList.of(ConventionTraitDef.INSTANCE, RelCollationTraitDef.INSTANCE);
        for (RelTraitDef i : TRAITS) {
            planner.addRelTraitDef(i);
        }
        return RelOptCluster.create(planner, MycatCalciteSupport.RexBuilder);
    }

    private static final RelOptTable.ViewExpander NOOP_EXPANDER = (rowType, queryString, schemaPath, viewPath) -> null;

}