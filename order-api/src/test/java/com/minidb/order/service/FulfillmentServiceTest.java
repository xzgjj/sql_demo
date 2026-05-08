package com.minidb.order.service;

import com.minidb.order.domain.FulfillmentStatus;
import com.minidb.order.infra.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FulfillmentServiceTest {

    @Autowired private FulfillmentService fulfillmentService;
    @Autowired private JdbcTemplate jdbc;

    private long userId = 502L;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM fulfillment_tasks");
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM order_status_logs");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("UPDATE product_inventory SET available_qty=100, locked_qty=0, shipped_qty=0 WHERE product_id IN (1001,1002,1003)");
    }

    @Test
    void shouldListTasks() {
        seedTask(10); // PENDING_CLAIM
        seedTask(10);

        var page = fulfillmentService.listTasks(null, 1, 20);
        assertTrue(page.total() >= 2);
        for (var t : page.items()) {
            assertNotNull(t.taskNo());
            assertNotNull(t.orderNo());
        }
    }

    @Test
    void shouldFilterTasksByStatus() {
        seedTask(FulfillmentStatus.PENDING_CLAIM.getCode());
        seedTask(FulfillmentStatus.PICKING.getCode());

        var pendingPage = fulfillmentService.listTasks(FulfillmentStatus.PENDING_CLAIM.getCode(), 1, 20);
        assertEquals(1, pendingPage.total());

        var pickingPage = fulfillmentService.listTasks(FulfillmentStatus.PICKING.getCode(), 1, 20);
        assertEquals(1, pickingPage.total());
    }

    @Test
    void shouldPickTask() {
        long taskId = seedTask(FulfillmentStatus.PICKING.getCode());

        // Assign to user
        jdbc.update("UPDATE fulfillment_tasks SET assignee_id = ? WHERE id = ?", userId, taskId);

        fulfillmentService.pickTask(taskId, userId);

        var detail = fulfillmentService.getTask(taskId);
        assertEquals(FulfillmentStatus.PICKED.getCode(), detail.status());
    }

    @Test
    void shouldRejectPickWhenNotAssigned() {
        long taskId = seedTask(FulfillmentStatus.PICKING.getCode());

        assertThrows(BusinessException.class,
            () -> fulfillmentService.pickTask(taskId, 999L),
            "Should reject pick by non-assignee");
    }

    @Test
    void shouldRejectPickWhenNotInPicking() {
        long taskId = seedTask(FulfillmentStatus.PENDING_CLAIM.getCode());
        jdbc.update("UPDATE fulfillment_tasks SET assignee_id = ? WHERE id = ?", userId, taskId);

        assertThrows(BusinessException.class,
            () -> fulfillmentService.pickTask(taskId, userId),
            "Should reject pick when not in PICKING status");
    }

    private long seedTask(int status) {
        String orderNo = "ORD" + System.nanoTime();
        jdbc.update("INSERT INTO orders (order_no, user_id, status, total_amount, remark, expires_at, version) " +
            "VALUES (?, ?, 10, 89.00, 'test', NOW() + INTERVAL '30' MINUTE, 0)",
            orderNo, userId);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);

        String taskNo = "FUL" + System.nanoTime();
        jdbc.update("INSERT INTO fulfillment_tasks (user_id, task_no, order_id, warehouse_id, status, assignee_id, version) " +
            "VALUES (?, ?, ?, 1, ?, ?, 0)",
            userId, taskNo, orderId, status, status == FulfillmentStatus.PICKING.getCode() ? userId : null);
        return jdbc.queryForObject("SELECT id FROM fulfillment_tasks WHERE task_no = ?", Long.class, taskNo);
    }
}
