package com.minidb.proxy.router;

import com.minidb.proxy.parser.ParsedSql;
import com.minidb.proxy.pool.DataSourceId;
import com.minidb.proxy.session.ProxySession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqlRouterImpl {

    private static final Logger log = LoggerFactory.getLogger(SqlRouterImpl.class);

    static final int CROSS_SHARD_TXN = 5001;
    static final int MISSING_SHARD_KEY = 5002;

    private final int shardCount;
    private final long readAfterWriteWindowMs;

    public SqlRouterImpl(int shardCount, long readAfterWriteWindowMs) {
        this.shardCount = shardCount;
        this.readAfterWriteWindowMs = readAfterWriteWindowMs;
    }

    public RoutePlan route(ProxySession session, ParsedSql sql) {
        // Transaction commands handled purely in session
        if (sql.isTxCommand()) {
            return RoutePlan.sessionOnlyPlan();
        }

        // Inside a transaction: bind to existing connection or assign one
        if (session.inTransaction()) {
            return routeWithinTransaction(session, sql);
        }

        // Outside a transaction
        return routeOutsideTransaction(session, sql);
    }

    private RoutePlan routeWithinTransaction(ProxySession session, ParsedSql sql) {
        DataSourceId boundDs = session.boundConnection() != null
                ? session.boundConnection().dataSourceId() : null;

        // Already bound: must stay within same shard
        if (boundDs != null && session.boundShardId() >= 0) {
            Long requestShard = sql.shardKey();
            if (requestShard != null) {
                int requestShardId = (int) (requestShard % shardCount);
                if (requestShardId != session.boundShardId()) {
                    String msg = String.format(
                            "CROSS_SHARD_UNSUPPORTED: transaction bound to shard_%d, but request targets shard_%d (user_id=%d %% %d = %d)",
                            session.boundShardId(), requestShardId,
                            requestShard, shardCount, requestShardId);
                    log.warn(msg);
                    throw new CrossShardException(CROSS_SHARD_TXN, msg);
                }
            }
            return RoutePlan.toDataSource(boundDs, session.boundShardId());
        }

        // Not yet bound: assign based on SQL.
        // Non-DML commands (SET, SHOW) inside tx without a bound connection:
        // default to PRIMARY without requiring shard key.
        if (!sql.isWrite() && !sql.isRead()) {
            return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
        }

        DataSourceId ds = resolveWriteDataSource(sql);
        Integer shardId = resolveShardId(sql);

        if (shardId == null) {
            throw new CrossShardException(MISSING_SHARD_KEY,
                    "MISSING_SHARD_KEY: shard key (user_id) required within transaction");
        }

        return RoutePlan.toDataSource(ds, shardId);
    }

    private RoutePlan routeOutsideTransaction(ProxySession session, ParsedSql sql) {
        if (sql.isWrite()) {
            return RoutePlan.toDataSource(primaryForSql(sql), resolveShardId(sql));
        }

        if (sql.isRead()) {
            // SELECT ... FOR UPDATE goes to primary
            if (sql.hasForUpdate()) {
                return RoutePlan.toDataSource(primaryForSql(sql), resolveShardId(sql));
            }

            // Write-then-read: go to primary to avoid replication lag
            if (session.isRecentlyWritten(readAfterWriteWindowMs)) {
                log.debug("Session {} recently wrote, routing read to primary", session.sessionId());
                return RoutePlan.toDataSource(primaryForSql(sql), resolveShardId(sql));
            }

            return RoutePlan.toDataSource(replicaForSql(sql), resolveShardId(sql));
        }

        // SET, SHOW, etc. — default to primary
        return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
    }

    private DataSourceId resolveWriteDataSource(ParsedSql sql) {
        return dataSourceForSql(sql, DataSourceId.PRIMARY);
    }

    private DataSourceId primaryForSql(ParsedSql sql) {
        return dataSourceForSql(sql, DataSourceId.PRIMARY);
    }

    private DataSourceId replicaForSql(ParsedSql sql) {
        return dataSourceForSql(sql, DataSourceId.REPLICA);
    }

    private DataSourceId dataSourceForSql(ParsedSql sql, DataSourceId fallback) {
        Integer shardId = resolveShardId(sql);
        return shardId != null ? DataSourceId.shard(shardId) : fallback;
    }
        Long key = sql.shardKey();
        if (key == null) return null;
        return (int) (key % shardCount);
    }

    public static class CrossShardException extends RuntimeException {
        private final int errorCode;

        public CrossShardException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public int errorCode() { return errorCode; }
    }
}
