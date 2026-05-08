package com.minidb.mvcc;

import com.minidb.mvcc.VersionedKVStore;
import com.minidb.mvcc.IsolationLevel;
import com.minidb.mvcc.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioRunnerTest {
    private TransactionManager txnManager;
    private VersionedKVStore store;
    private ScenarioRunner runner;

    @BeforeEach
    void setUp() {
        txnManager = new TransactionManager();
        store = new VersionedKVStore(txnManager);
        runner = new ScenarioRunner(txnManager, store);
    }

    @Test
    void dirtyReadPrevention() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "A", bytes("100")),
                ScenarioStep.begin("T2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.get("T2", "A", "first-read"),
                ScenarioStep.commit("T1"),
                ScenarioStep.get("T2", "A", "after-commit")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());

        var t2first = result.trace().stream()
                .filter(e -> "first-read => NOT_FOUND".equals(e.detail()))
                .findFirst();
        assertTrue(t2first.isPresent(), "T2 should not see T1's uncommitted write");

        var t2after = result.trace().stream()
                .filter(e -> e.detail() != null && e.detail().contains("after-commit => 100"))
                .findFirst();
        assertTrue(t2after.isPresent(), "T2 should see T1's committed write in RC");
    }

    @Test
    void repeatableReadDemo() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.put("T1", "A", bytes("10")),
                ScenarioStep.commit("T1"),

                ScenarioStep.begin("T2", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.get("T2", "A", "rr-read-1"),

                ScenarioStep.begin("T3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T3", "A", bytes("20")),
                ScenarioStep.commit("T3"),

                ScenarioStep.get("T2", "A", "rr-read-2")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());

        var rr1 = result.trace().stream()
                .filter(e -> e.detail() != null && e.detail().contains("rr-read-1 =>"))
                .findFirst().get();
        var rr2 = result.trace().stream()
                .filter(e -> e.detail() != null && e.detail().contains("rr-read-2 =>"))
                .findFirst().get();

        assertEquals("10", extractValue(rr1.detail()));
        assertEquals("10", extractValue(rr2.detail()),
                "RR should see same value even after T3 committed");
    }

    @Test
    void writeConflictDemo() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "A", bytes("20")),
                ScenarioStep.begin("T2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T2", "A", bytes("30"))
        );

        ScenarioResult result = runner.run(steps);
        assertTrue(result.hasErrors(), "Expected write conflict: " + result.errors());
        assertTrue(result.errors().get(0).contains("Write conflict"),
                "Error should mention write conflict: " + result.errors().get(0));
    }

    @Test
    void rollbackDemo() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "A", bytes("old")),
                ScenarioStep.commit("T1"),

                ScenarioStep.begin("T2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T2", "A", bytes("new")),
                ScenarioStep.rollback("T2"),

                ScenarioStep.begin("T3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.get("T3", "A", "after-rollback")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());

        var t3read = result.trace().stream()
                .filter(e -> e.detail() != null && e.detail().contains("after-rollback =>"))
                .findFirst();
        assertTrue(t3read.isPresent());
        assertEquals("old", extractValue(t3read.get().detail()));
    }

    @Test
    void versionChainDemo() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "A", bytes("v1")),
                ScenarioStep.commit("T1"),

                ScenarioStep.begin("T2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T2", "A", bytes("v2")),
                ScenarioStep.commit("T2"),

                ScenarioStep.begin("T3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T3", "A", bytes("v3")),
                ScenarioStep.commit("T3")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());
        assertTrue(result.versionChains().containsKey("A"));
        assertFalse(result.versionChains().get("A").isEmpty());
        assertEquals("v3", str(result.versionChains().get("A").get(0).value()));
    }

    @Test
    void traceContainsAllKeyOperations() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "B", bytes("value")),
                ScenarioStep.get("T1", "B", "check"),
                ScenarioStep.commit("T1")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());

        List<String> ops = result.trace().stream()
                .map(e -> e.operation())
                .toList();
        assertTrue(ops.contains("BEGIN"));
        assertTrue(ops.contains("PUT"));
        assertTrue(ops.contains("GET"));
        assertTrue(ops.contains("COMMIT"));
    }

    @Test
    void deleteThenReadShouldReturnEmpty() {
        var steps = List.of(
                ScenarioStep.begin("T1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("T1", "A", bytes("data")),
                ScenarioStep.commit("T1"),

                ScenarioStep.begin("T2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.delete("T2", "A"),
                ScenarioStep.commit("T2"),

                ScenarioStep.begin("T3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.get("T3", "A", "after-delete")
        );

        ScenarioResult result = runner.run(steps);
        assertFalse(result.hasErrors());

        var t3read = result.trace().stream()
                .filter(e -> e.detail() != null && e.detail().contains("NOT_FOUND"))
                .findFirst();
        assertTrue(t3read.isPresent(), "Deleted key should return NOT_FOUND");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String extractValue(String detail) {
        if (detail == null) return null;
        int idx = detail.lastIndexOf("=> ");
        if (idx < 0) return null;
        String val = detail.substring(idx + 3).trim();
        return val.equals("NOT_FOUND") ? null : val;
    }
}
