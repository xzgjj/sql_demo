package com.minidb.proxy.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlParserImplTest {

    private SqlParserImpl parser;

    @BeforeEach
    void setUp() {
        parser = new SqlParserImpl();
    }

    @Test
    void shouldParseSelect() {
        ParsedSql result = parser.parse("SELECT * FROM orders WHERE user_id = 100");
        assertEquals(SqlType.SELECT, result.type());
        assertTrue(result.tables().contains("orders"));
        assertEquals(100L, result.shardKey());
    }

    @Test
    void shouldParseInsert() {
        ParsedSql result = parser.parse(
                "INSERT INTO orders (id, user_id, amount) VALUES (1, 100, 29.90)");
        assertEquals(SqlType.INSERT, result.type());
        assertTrue(result.tables().contains("orders"));
        assertEquals(100L, result.shardKey());
    }

    @Test
    void shouldParseUpdate() {
        ParsedSql result = parser.parse(
                "UPDATE orders SET status = 20 WHERE user_id = 100 AND id = 1");
        assertEquals(SqlType.UPDATE, result.type());
        assertTrue(result.tables().contains("orders"));
        assertEquals(100L, result.shardKey());
    }

    @Test
    void shouldParseDelete() {
        ParsedSql result = parser.parse("DELETE FROM orders WHERE user_id = 100");
        assertEquals(SqlType.DELETE, result.type());
        assertEquals(100L, result.shardKey());
    }

    @Test
    void shouldParseBeginCommitRollback() {
        assertEquals(SqlType.BEGIN, parser.parse("BEGIN").type());
        assertTrue(parser.parse("BEGIN").isTxCommand());

        assertEquals(SqlType.COMMIT, parser.parse("COMMIT").type());
        assertTrue(parser.parse("COMMIT").isTxCommand());

        assertEquals(SqlType.ROLLBACK, parser.parse("ROLLBACK").type());
        assertTrue(parser.parse("ROLLBACK").isTxCommand());
    }

    @Test
    void shouldReturnNullShardKeyWhenMissing() {
        ParsedSql result = parser.parse("SELECT * FROM orders");
        assertEquals(SqlType.SELECT, result.type());
        assertNull(result.shardKey());
    }

    @Test
    void shouldParseSelectForUpdate() {
        ParsedSql result = parser.parse(
                "SELECT * FROM orders WHERE user_id = 100 FOR UPDATE");
        assertEquals(SqlType.SELECT, result.type());
        assertTrue(result.hasForUpdate());
    }

    @Test
    void shouldHandleBlankSql() {
        ParsedSql result = parser.parse("");
        assertEquals(SqlType.OTHER, result.type());
    }

    @Test
    void shouldHandleNullSql() {
        ParsedSql result = parser.parse(null);
        assertEquals(SqlType.OTHER, result.type());
    }

    @Test
    void shouldParseSetCommand() {
        ParsedSql result = parser.parse("SET autocommit=1");
        assertEquals(SqlType.SET, result.type());
    }

    @Test
    void shouldHandleInvalidSqlGracefully() {
        ParsedSql result = parser.parse("GARBAGE SYNTAX !!!");
        assertEquals(SqlType.OTHER, result.type());
    }

    @Test
    void shouldClassifyIsWrite() {
        assertTrue(parser.parse("INSERT INTO t VALUES (1)").isWrite());
        assertTrue(parser.parse("UPDATE t SET x=1 WHERE user_id=1").isWrite());
        assertTrue(parser.parse("DELETE FROM t WHERE user_id=1").isWrite());
        assertFalse(parser.parse("SELECT 1").isWrite());
    }

    @Test
    void shouldClassifyIsRead() {
        assertTrue(parser.parse("SELECT 1").isRead());
        assertFalse(parser.parse("INSERT INTO t VALUES (1)").isRead());
    }
}
