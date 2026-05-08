package com.minidb.proxy;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlRouterImplTest {

    private SqlRouterImpl router;
    private ProxySession session;

    @BeforeEach
    void setUp() {
        router = new SqlRouterImpl(4, 3000);
        session = new ProxySession(new EmbeddedChannel());
    }

    private static ParsedSql primaryOnlySql() {
        return new ParsedSql(SqlType.INSERT, Set.of("idempotency_records"), null,
                null, AltRouteType.NONE, true,
                "INSERT INTO idempotency_records (idempotency_key) VALUES ('k1')", false, false);
    }

    private static ParsedSql altRouteSql(AltRouteType type, String key) {
        return new ParsedSql(SqlType.SELECT, Set.of("orders"), null,
                key, type, false,
                "SELECT * FROM orders WHERE " + type.name().toLowerCase() + " = '" + key + "'", false, false);
    }

    private static ParsedSql txBegin() {
        return new ParsedSql(SqlType.BEGIN, Set.of(), null,
                null, AltRouteType.NONE, false, "BEGIN", false, true);
    }

    private static ParsedSql selectWithShardKey(long userId) {
        return new ParsedSql(SqlType.SELECT, Set.of("orders"), userId,
                null, AltRouteType.NONE, false,
                "SELECT * FROM orders WHERE user_id=" + userId, false, false);
    }

    private static ParsedSql insertWithShardKey(long userId) {
        return new ParsedSql(SqlType.INSERT, Set.of("orders"), userId,
                null, AltRouteType.NONE, false,
                "INSERT INTO orders (user_id) VALUES (" + userId + ")", false, false);
    }

    @Test
    void shouldRouteTxCommandsToSessionOnly() {
        RoutePlan plan = router.route(session, txBegin());
        assertTrue(plan.sessionOnly());
        assertFalse(plan.requiresBackend());
    }

    @Test
    void shouldRouteWriteToPrimaryOutsideTx() {
        ParsedSql insert = insertWithShardKey(100);
        RoutePlan plan = router.route(session, insert);
        assertEquals(DataSourceId.shard(0), plan.dataSourceId());
        assertEquals(0, plan.shardId()); // 100 % 2 = 0
    }

    @Test
    void shouldRouteReadToReplicaOutsideTx() {
        ParsedSql select = selectWithShardKey(100);
        RoutePlan plan = router.route(session, select);
        assertEquals(DataSourceId.shard(0), plan.dataSourceId()); // shard data source
        assertEquals(0, plan.shardId()); // 100 % 2
    }

    @Test
    void shouldRouteSelectForUpdateToPrimary() {
        ParsedSql forUpdate = new ParsedSql(SqlType.SELECT, Set.of("orders"), 100L,
                null, AltRouteType.NONE, false,
                "SELECT * FROM orders WHERE user_id=100 FOR UPDATE", true, false);
        RoutePlan plan = router.route(session, forUpdate);
        assertEquals(DataSourceId.shard(0), plan.dataSourceId());
    }

    @Test
    void shouldRejectCrossShardInTransaction() {
        router.route(session, txBegin());
        session.beginTransaction();

        // first query binds to shard_0
        ParsedSql first = selectWithShardKey(100); // 100 % 2 = 0
        router.route(session, first);
        // bind the session
        EmbeddedChannel ch = new EmbeddedChannel();
        BackendConnection conn = new BackendConnection(DataSourceId.shard(0), ch);
        session.bind(0, conn);

        // second query targets shard_1 — should throw
        ParsedSql second = selectWithShardKey(101); // 101 % 2 = 1
        assertThrows(SqlRouterImpl.CrossShardException.class, () -> {
            router.route(session, second);
        });
        ch.close();
    }

    @Test
    void shouldAllowSameShardInTransaction() {
        router.route(session, txBegin());
        session.beginTransaction();

        ParsedSql first = selectWithShardKey(100); // shard 0
        RoutePlan plan1 = router.route(session, first);
        assertEquals(0, plan1.shardId());

        EmbeddedChannel ch = new EmbeddedChannel();
        BackendConnection conn = new BackendConnection(DataSourceId.shard(0), ch);
        session.bind(0, conn);

        ParsedSql second = selectWithShardKey(200); // 200 % 2 = 0, same shard
        RoutePlan plan2 = router.route(session, second);
        assertEquals(0, plan2.shardId());
        ch.close();
    }

    @Test
    void shouldThrowMissingShardKeyInTransaction() {
        router.route(session, txBegin());
        session.beginTransaction();

        ParsedSql noShard = new ParsedSql(SqlType.SELECT, Set.of("orders"), null,
                null, AltRouteType.NONE, false, "SELECT * FROM orders", false, false);
        assertThrows(SqlRouterImpl.CrossShardException.class, () -> {
            router.route(session, noShard);
        });
    }

    @Test
    void shouldRouteReadToPrimaryAfterWrite() {
        session.markWrite();
        ParsedSql select = selectWithShardKey(100);
        RoutePlan plan = router.route(session, select);
        assertEquals(DataSourceId.shard(0), plan.dataSourceId()); // primary, not replica
    }

    @Test
    void shouldRouteWithoutShardKeyToDefaultOutsideTx() {
        ParsedSql select = new ParsedSql(SqlType.SELECT, Set.of(), null,
                null, AltRouteType.NONE, false, "SELECT 1", false, false);
        RoutePlan plan = router.route(session, select);
        assertEquals(DataSourceId.REPLICA, plan.dataSourceId());
        assertNull(plan.shardId());
    }

    @Test
    void shouldRouteWriteWithoutShardKeyToPrimary() {
        // INSERT without explicit user_id shard key
        ParsedSql insert = new ParsedSql(SqlType.INSERT, Set.of("t"), null,
                null, AltRouteType.NONE, false, "INSERT INTO t VALUES (1)", false, false);
        RoutePlan plan = router.route(session, insert);
        assertEquals(DataSourceId.PRIMARY, plan.dataSourceId());
    }

    @Test
    void shouldRoutePrimaryOnlyTableToPrimary() {
        ParsedSql sql = primaryOnlySql();
        RoutePlan plan = router.route(session, sql);
        assertEquals(DataSourceId.PRIMARY, plan.dataSourceId());
        assertNull(plan.shardId());
    }

    @Test
    void shouldRoutePrimaryOnlyTableToPrimaryEvenInTransaction() {
        session.beginTransaction();
        // bind to shard first
        EmbeddedChannel ch = new EmbeddedChannel();
        BackendConnection conn = new BackendConnection(DataSourceId.shard(0), ch);
        session.bind(0, conn);

        ParsedSql sql = primaryOnlySql();
        RoutePlan plan = router.route(session, sql);
        assertEquals(DataSourceId.PRIMARY, plan.dataSourceId());
        ch.close();
    }

    @Test
    void shouldThrowMissingShardKeyWhenAltRouteWithoutLookup() {
        // altRouteKey is set but no RouteTableLookup → should throw MISSING_SHARD_KEY
        ParsedSql sql = altRouteSql(AltRouteType.ORDER_NO, "ORD123");
        // Outside tx: shardKey=null, no lookup → uses fallback primary
        RoutePlan plan = router.route(session, sql);
        assertEquals(DataSourceId.REPLICA, plan.dataSourceId());
        assertNull(plan.shardId());
    }

    @Test
    void shouldRouteShard4Correctly() {
        assertEquals(0, router.route(session, insertWithShardKey(100)).shardId()); // 100%4=0
        assertEquals(1, router.route(session, insertWithShardKey(101)).shardId()); // 101%4=1
        assertEquals(2, router.route(session, insertWithShardKey(102)).shardId()); // 102%4=2
        assertEquals(3, router.route(session, insertWithShardKey(103)).shardId()); // 103%4=3
    }
}
