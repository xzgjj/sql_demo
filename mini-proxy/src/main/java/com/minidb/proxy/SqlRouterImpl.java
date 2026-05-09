package com.minidb.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqlRouterImpl {

    private static final Logger log = LoggerFactory.getLogger(SqlRouterImpl.class);

    static final int CROSS_SHARD_TXN = 5001;
    static final int MISSING_SHARD_KEY = 5002;
    static final int ROUTE_NOT_FOUND = 5003;
    static final int ROUTE_LOOKUP_FAILED = 5004;

    private final int shardCount;
    private final long readAfterWriteWindowMs;
    private final RouteTableLookup routeTableLookup;

    public SqlRouterImpl(int shardCount, long readAfterWriteWindowMs) {
        this(shardCount, readAfterWriteWindowMs, null);
    }

    public SqlRouterImpl(int shardCount, long readAfterWriteWindowMs, RouteTableLookup routeTableLookup) {
        this.shardCount = shardCount;
        this.readAfterWriteWindowMs = readAfterWriteWindowMs;
        this.routeTableLookup = routeTableLookup;
    }

    public RoutePlan route(ProxySession session, ParsedSql sql) {
        // Transaction commands handled purely in session
        if (sql.isTxCommand()) {
            return RoutePlan.sessionOnlyPlan();
        }

        // Resolve shard key (may trigger two-phase routing via order_route lookup)
        Long shardKey = resolveShardKey(session, sql);

        // Inside a transaction: bind to existing connection or assign one
        if (session.inTransaction()) {
            return routeWithinTransaction(session, sql, shardKey);
        }

        // PRIMARY-only tables: always route to PRIMARY, no shard key needed
        if (sql.isPrimaryOnly()) {
            return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
        }

        // Outside a transaction
        return routeOutsideTransaction(session, sql, shardKey);
    }

    private Long resolveShardKey(ProxySession session, ParsedSql sql) {
        Long key = sql.shardKey();
        if (key != null) return key;

        // Two-phase route lookup: no user_id, but have order_no or payment_no
        if (sql.hasAltRouteKey() && routeTableLookup != null) {
            String altKey = sql.altRouteKey();
            Long userId;
            try {
                if (sql.altRouteType() == AltRouteType.ORDER_NO) {
                    userId = routeTableLookup.lookupByOrderNo(altKey);
                } else if (sql.altRouteType() == AltRouteType.PAYMENT_NO) {
                    userId = routeTableLookup.lookupByPaymentNo(altKey);
                } else {
                    userId = null;
                }
            } catch (RouteTableLookup.RouteLookupException e) {
                log.error("Route lookup failed: {}", e.getMessage());
                throw new CrossShardException(ROUTE_LOOKUP_FAILED,
                        "ROUTE_LOOKUP_FAILED: " + sql.altRouteType() + "=" + altKey
                                + " — PRIMARY route metadata unavailable: " + e.getMessage());
            }

            if (userId == null) {
                throw new CrossShardException(ROUTE_NOT_FOUND,
                        "ROUTE_NOT_FOUND: cannot find route for " + sql.altRouteType()
                                + "=" + altKey + ". Please provide user_id directly.");
            }

            log.info("Two-phase route: {}='{}' → user_id={} → shard_{}",
                    sql.altRouteType(), altKey, userId, userId % shardCount);
            return userId;
        }

        return null;
    }

    private RoutePlan routeWithinTransaction(ProxySession session, ParsedSql sql, Long shardKey) {
        DataSourceId boundDs = session.boundConnection() != null
                ? session.boundConnection().dataSourceId() : null;
        RoutePlan requestedPlan = requestedPlanWithinTransaction(sql, shardKey);

        // Already bound: must stay on the same physical data source.
        if (boundDs != null) {
            if (!boundDs.equals(requestedPlan.dataSourceId())) {
                String msg = String.format(
                        "CROSS_DATASOURCE_TX_UNSUPPORTED: transaction bound to %s, but request targets %s",
                        boundDs.name(), requestedPlan.dataSourceId().name());
                log.warn(msg);
                throw new CrossShardException(CROSS_SHARD_TXN, msg);
            }
            if (requestedPlan.shardId() != null
                    && session.boundShardId() >= 0
                    && !requestedPlan.shardId().equals(session.boundShardId())) {
                String msg = String.format(
                        "CROSS_SHARD_UNSUPPORTED: transaction bound to shard_%d, but request targets shard_%d (user_id=%d %% %d = %d)",
                        session.boundShardId(), requestedPlan.shardId(),
                        shardKey, shardCount, requestedPlan.shardId());
                log.warn(msg);
                throw new CrossShardException(CROSS_SHARD_TXN, msg);
            }
            return RoutePlan.toDataSource(boundDs, session.boundShardId() >= 0 ? session.boundShardId() : null);
        }

        return requestedPlan;
    }

    private RoutePlan requestedPlanWithinTransaction(ParsedSql sql, Long shardKey) {
        if (sql.isPrimaryOnly()) {
            return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
        }

        // Non-DML commands (SET, SHOW) inside tx without a bound connection:
        // default to PRIMARY without requiring shard key.
        if (!sql.isWrite() && !sql.isRead()) {
            return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
        }

        Integer shardId = resolveShardIdFromKey(shardKey);
        if (shardId == null) {
            throw new CrossShardException(MISSING_SHARD_KEY,
                    "MISSING_SHARD_KEY: shard key (user_id) required within transaction. "
                    + "Provide user_id or one of order_no/payment_no for route lookup.");
        }
        return RoutePlan.toDataSource(resolveWriteDataSource(sql, shardKey), shardId);
    }

    private RoutePlan routeOutsideTransaction(ProxySession session, ParsedSql sql, Long shardKey) {
        if (requiresShardKey(sql, shardKey)) {
            throw new CrossShardException(MISSING_SHARD_KEY,
                    "MISSING_SHARD_KEY: shard key (user_id) required for sharded table access. "
                    + "Provide user_id or one of order_no/payment_no for route lookup.");
        }

        if (sql.isWrite()) {
            return RoutePlan.toDataSource(primaryForSql(sql, shardKey), resolveShardIdFromKey(shardKey));
        }

        if (sql.isRead()) {
            // SELECT ... FOR UPDATE goes to primary
            if (sql.hasForUpdate()) {
                return RoutePlan.toDataSource(primaryForSql(sql, shardKey), resolveShardIdFromKey(shardKey));
            }

            // Write-then-read: go to primary to avoid replication lag
            if (session.isRecentlyWritten(readAfterWriteWindowMs)) {
                log.debug("Session {} recently wrote, routing read to primary", session.sessionId());
                return RoutePlan.toDataSource(primaryForSql(sql, shardKey), resolveShardIdFromKey(shardKey));
            }

            return RoutePlan.toDataSource(replicaForSql(sql, shardKey), resolveShardIdFromKey(shardKey));
        }

        // SET, SHOW, etc. — default to primary
        return RoutePlan.toDataSource(DataSourceId.PRIMARY, null);
    }

    private boolean requiresShardKey(ParsedSql sql, Long shardKey) {
        return (sql.isWrite() || sql.isRead())
                && !sql.tables().isEmpty()
                && !sql.isPrimaryOnly()
                && shardKey == null;
    }

    private DataSourceId resolveWriteDataSource(ParsedSql sql, Long shardKey) {
        return dataSourceForSql(sql, shardKey, DataSourceId.PRIMARY);
    }

    private DataSourceId primaryForSql(ParsedSql sql, Long shardKey) {
        return dataSourceForSql(sql, shardKey, DataSourceId.PRIMARY);
    }

    private DataSourceId replicaForSql(ParsedSql sql, Long shardKey) {
        return dataSourceForSql(sql, shardKey, DataSourceId.REPLICA);
    }

    private DataSourceId dataSourceForSql(ParsedSql sql, Long shardKey, DataSourceId fallback) {
        Integer shardId = resolveShardIdFromKey(shardKey);
        return shardId != null ? DataSourceId.shard(shardId) : fallback;
    }

    private Integer resolveShardIdFromKey(Long key) {
        if (key == null) return null;
        if (key < 0) {
            throw new CrossShardException(MISSING_SHARD_KEY,
                    "INVALID_SHARD_KEY: user_id must be non-negative");
        }
        return (int) Math.floorMod(key, shardCount);
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
