package com.minidb.proxy.router;

import com.minidb.proxy.parser.ParsedSql;
import com.minidb.proxy.parser.SqlType;
import com.minidb.proxy.pool.BackendConnection;
import com.minidb.proxy.pool.DataSourceId;
import com.minidb.proxy.session.ProxySession;
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
        router = new SqlRouterImpl(2, 3000);
        session = new ProxySession(new EmbeddedChannel());
    }

    private static ParsedSql txBegin() {
        return new ParsedSql(SqlType.BEGIN, Set.of(), null, "BEGIN", false, true);
    }

    private static ParsedSql txCommit() {
        return new ParsedSql(SqlType.COMMIT, Set.of(), null, "COMMIT", false, true);
    }

    private static ParsedSql selectWithShardKey(long userId) {
        return new ParsedSql(SqlType.SELECT, Set.of("orders"), userId,
                "SELECT * FROM orders WHERE user_id=" + userId, false, false);
    }

    private static ParsedSql insertWithShardKey(long userId) {
        return new ParsedSql(SqlType.INSERT, Set.of("orders"), userId,
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
        RoutePlan plan1 = router.route(session, first);
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
                "SELECT * FROM orders", false, false);
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
                "SELECT 1", false, false);
        RoutePlan plan = router.route(session, select);
        assertEquals(DataSourceId.REPLICA, plan.dataSourceId());
        assertNull(plan.shardId());
    }

    @Test
    void shouldRouteWriteWithoutShardKeyToPrimary() {
        // INSERT without explicit user_id shard key
        ParsedSql insert = new ParsedSql(SqlType.INSERT, Set.of("t"), null,
                "INSERT INTO t VALUES (1)", false, false);
        RoutePlan plan = router.route(session, insert);
        assertEquals(DataSourceId.PRIMARY, plan.dataSourceId());
    }
}
