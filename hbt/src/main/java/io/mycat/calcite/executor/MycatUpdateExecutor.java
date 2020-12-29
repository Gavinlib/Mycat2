package io.mycat.calcite.executor;

import com.alibaba.fastsql.sql.ast.SQLStatement;
import com.alibaba.fastsql.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.fastsql.sql.ast.statement.SQLExprTableSource;
import com.alibaba.fastsql.sql.ast.statement.SQLUpdateStatement;
import io.mycat.*;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import io.mycat.DataNode;
import io.mycat.MycatConnection;
import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;

import io.mycat.calcite.DataSourceFactory;
import io.mycat.calcite.Executor;
import io.mycat.calcite.ExplainWriter;
import io.mycat.calcite.rewriter.Distribution;
import io.mycat.gsi.GSIService;
import io.mycat.mpp.Row;
import io.mycat.sqlrecorder.SqlRecord;
import io.mycat.util.FastSqlUtils;
import io.mycat.util.Pair;
import io.mycat.util.SQL;
import io.mycat.util.UpdateSQL;
import lombok.Getter;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static io.mycat.calcite.executor.MycatPreparedStatementUtil.apply;

@Getter
public class MycatUpdateExecutor implements Executor {

    private final MycatDataContext context;
    private final Distribution distribution;
    /**
     * 逻辑语法树（用户在前端写的SQL语句）
     */
    private final SQLStatement logicStatement;
    /**
     * 逻辑参数 （用户在前端写的SQL语句中的参数）
     */
    private final List<Object> logicParameters;
    /**
     * 由逻辑SQL 改成真正发送给后端数据库的sql语句. 一个不可变的集合 {@link Collections#unmodifiableSet(Set)}
     */
    private final Set<SQL> reallySqlSet;
    private final DataSourceFactory factory;

    private long lastInsertId = 0;
    private long affectedRow = 0;
    private static final Logger LOGGER = LoggerFactory.getLogger(MycatUpdateExecutor.class);

    public MycatUpdateExecutor(MycatDataContext context, Distribution distribution,
                               SQLStatement logicStatement,
                               List<Object> parameters,
                               DataSourceFactory factory) {
        this.context = context;

        this.distribution = distribution;
        this.logicStatement = logicStatement;
        this.logicParameters = parameters;

        this.factory = factory;
        this.reallySqlSet = Collections.unmodifiableSet(buildReallySqlList(distribution,logicStatement,parameters));
        factory.registered(reallySqlSet.stream().map(SQL::getTarget).distinct().collect(Collectors.toList()));
    }

    public static MycatUpdateExecutor create(MycatDataContext context, Distribution values,
                                             SQLStatement sqlStatement,
                                             DataSourceFactory factory,
                                             List<Object> parameters) {
        return new MycatUpdateExecutor(context, values, sqlStatement, parameters, factory);
    }

    public boolean isProxy() {
        return reallySqlSet.size() == 1;
    }

    public Pair<String, String> getSingleSql() {
        SQL key = reallySqlSet.iterator().next();
        String parameterizedSql = key.getParameterizedSql();
        String sql = apply(parameterizedSql, logicParameters);
        return Pair.of(key.getTarget(), sql);
    }

    private FastSqlUtils.Select getSelectPrimaryKeyStatementIfNeed(SQL sql){
        TableHandler table = sql.getTable();
        SQLStatement statement = sql.getStatement();
        if(statement instanceof SQLUpdateStatement) {
            return FastSqlUtils.conversionToSelectSql((SQLUpdateStatement) statement, table.getPrimaryKeyList(),sql.getParameters());
        }else if(statement instanceof SQLDeleteStatement){
            return FastSqlUtils.conversionToSelectSql((SQLDeleteStatement) statement,table.getPrimaryKeyList(),sql.getParameters());
        }
        throw new MycatException("更新语句转查询语句出错，不支持的语法。 \n sql = "+ statement);
    }

    @Override
    @SneakyThrows
    public void open() {
        TransactionSession transactionSession = context.getTransactionSession();
        Map<String, MycatConnection> connections = new HashMap<>(3);
        Set<String> uniqueValues = new HashSet<>();
        for (SQL sql : reallySqlSet) {
            String k = context.resolveDatasourceTargetName(sql.getTarget());
            if (uniqueValues.add(k)) {
                if (connections.put(sql.getTarget(), transactionSession.getConnection(k)) != null) {
                    throw new IllegalStateException("Duplicate key");
                }
            }
        }

        SqlRecord sqlRecord = context.currentSqlRecord();
        //建立targetName与连接的映射
        for (SQL sql : reallySqlSet) {
            String parameterizedSql = sql.getParameterizedSql();
            String target = sql.getTarget();

            MycatConnection mycatConnection = connections.get(target);
            Connection connection = mycatConnection.unwrap(Connection.class);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} targetName:{} sql:{} parameters:{} ", mycatConnection, target, parameterizedSql, logicParameters);

            }
            if (LOGGER.isDebugEnabled() && connection.isClosed()) {
                LOGGER.debug("{} has closed but still using", mycatConnection);
            }

            // 如果是更新语法. 例： update set id = 1
            if(sql instanceof UpdateSQL) {
                UpdateSQL updateSQL = (UpdateSQL) sql;
                // 如果用户修改了分片键
                if(updateSQL.isUpdateShardingKey()){
                    onUpdateShardingKey(updateSQL,connection,transactionSession);
                }

                // 如果用户修改了索引
                if(updateSQL.isUpdateIndex()){
                    onUpdateIndex(updateSQL,connection,transactionSession);
                }
            }

            long start = SqlRecord.now();
            SQL.UpdateResult updateResult = sql.executeUpdate(connection);
            Long lastInsertId = updateResult.getLastInsertId();
            int subAffectedRow = updateResult.getAffectedRow();
            sqlRecord.addSubRecord(parameterizedSql,start,SqlRecord.now(),target,subAffectedRow);
            this.affectedRow += subAffectedRow;
            if(lastInsertId != null && lastInsertId > 0) {
                this.lastInsertId = lastInsertId;
            }
        }
    }

    private void onUpdateShardingKey(UpdateSQL sql,Connection connection,TransactionSession transactionSession) throws SQLException {

    }

    private void onUpdateIndex(UpdateSQL<?> sql,Connection connection,TransactionSession transactionSession) throws SQLException {
        GSIService gsiService = MetaClusterCurrent.wrapper(GSIService.class);
        if(gsiService == null){
            return;
        }
        // 获取主键
        Collection<Map<SimpleColumnInfo, Object>> primaryKeyList;
        if(sql.isWherePrimaryKeyCovering()){
            // 条件满足覆盖主键
            primaryKeyList = sql.getWherePrimaryKeyList();
        }else {
            // 不满足覆盖主键 就查询后端数据库
            primaryKeyList = sql.selectPrimaryKey(connection);
        }

        // 更新索引
        // todo 更新语句包含limit或者order by的情况处理，等实现了全局索引再考虑实现。 wangzihaogithub 2020-12-29
        TableHandler table = sql.getTable();
        gsiService.updateByPrimaryKey(transactionSession.getTxId(),
                table.getSchemaName(),
                table.getTableName(),
                sql.getSetColumnMap(),
                primaryKeyList,sql.getTarget());
    }

    private static Set<SQL> buildReallySqlList(Distribution distribution, SQLStatement statement, List<Object> parameters) {
        Iterable<DataNode> dataNodes = distribution.getDataNodes(parameters);
        Map<SQL,SQL> sqlMap = new LinkedHashMap<>();

        for (DataNode dataNode : dataNodes) {
            SQLStatement cloneStatement = FastSqlUtils.clone(statement);

            SQLExprTableSource tableSource = FastSqlUtils.getTableSource(cloneStatement);
            tableSource.setExpr(dataNode.getTable());
            tableSource.setSchema(dataNode.getSchema());
            StringBuilder sqlStringBuilder = new StringBuilder();
            List<Object> cloneParameters = new ArrayList<>();
            MycatPreparedStatementUtil.collect(cloneStatement, sqlStringBuilder, parameters, cloneParameters);
            SQL sql = SQL.of(sqlStringBuilder.toString(),dataNode,cloneStatement,cloneParameters);

            SQL exist = sqlMap.put(sql, sql);
            if(exist != null){
                LOGGER.debug("remove exist sql = {}",exist);
            }
        }
        return new LinkedHashSet<>(sqlMap.keySet());
    }

    @Override
    public Row next() {
        return null;
    }

    @Override
    public void close() {

    }

    @Override
    public boolean isRewindSupported() {
        return false;
    }

    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        ExplainWriter explainWriter = writer.name(this.getClass().getName())
                .into();
        for (SQL sql : reallySqlSet) {
            String target = sql.getTarget();
            String parameterizedSql = sql.getParameterizedSql();
            explainWriter.item("target:" + target + " " + parameterizedSql, logicParameters);

        }
        return explainWriter.ret();
    }
}