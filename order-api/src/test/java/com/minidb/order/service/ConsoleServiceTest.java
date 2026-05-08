package com.minidb.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConsoleServiceTest {
    @Autowired
    private ConsoleService consoleService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM fulfillment_tasks");
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM order_status_logs");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM exception_tickets");
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("DELETE FROM order_route");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM inventory_journals");
        jdbc.execute("UPDATE product_inventory SET available_qty=100, locked_qty=0, shipped_qty=0");
    }

    @Test
    void shouldLoadDemoDataAndReturnDashboard() {
        var result = consoleService.loadDemoData();
        var repeated = consoleService.loadDemoData();

        assertTrue(result.orderNos().size() >= 5);
        assertEquals(result.orderNos(), repeated.orderNos());
        assertTrue(result.fulfillmentTasks() >= 1);
        assertTrue(result.exceptions() >= 1);
        assertTrue(countOrdersByStatus(30) >= 1);
        assertTrue(countOrdersByStatus(50) >= 1);
        assertTrue(countOrdersByStatus(90) >= 1);
        assertEquals(5, countOrdersByUser(501));

        var summary = consoleService.dashboardSummary();
        assertTrue(summary.ordersToday() >= 5);
        assertTrue(summary.openExceptions() >= 1);
        assertFalse(summary.statusDistribution().isEmpty());
    }

    @Test
    void shouldBuildOrderTraceFromRealTables() {
        consoleService.loadDemoData();
        Long orderId = jdbc.queryForObject("SELECT MIN(id) FROM orders", Long.class);

        var trace = consoleService.traceOrder(orderId);

        assertNotNull(trace.order().orderNo());
        assertTrue(trace.route().contains("shard_"));
        assertFalse(trace.outbox().isEmpty());
        assertFalse(trace.timeline().isEmpty());
    }

    @Test
    void shouldPreviewRoutesWithoutExecutingSql() {
        var userRoute = consoleService.previewRoute("SELECT * FROM orders WHERE user_id = 501");
        assertEquals("USER_ID", userRoute.keyType());
        assertEquals("shard_1", userRoute.target());
        assertFalse(userRoute.executed());

        var rejected = consoleService.previewRoute("SELECT * FROM orders");
        assertEquals("REJECT", rejected.target());
    }

    @Test
    void shouldRunMvccScenario() {
        var result = consoleService.runLabScenario("mvcc-rc-rr");

        assertEquals("mvcc-rc-rr", result.scenario());
        assertFalse(result.steps().isEmpty());
        assertFalse(result.mvccChains().isEmpty());
    }

    private long countOrdersByStatus(int status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    private long countOrdersByUser(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }
}
