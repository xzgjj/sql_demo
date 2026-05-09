package com.minidb.order.service;

import com.minidb.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutboxProcessorTest {

    @Autowired private OutboxProcessor outboxProcessor;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM fulfillment_tasks");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM order_status_logs");
        jdbc.execute("DELETE FROM orders");
    }

    @Test
    void shouldClaimAndDeliverOrderPaidEvent() {
        long orderId = seedPaidOrder();
        jdbc.update("INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload, status) " +
            "VALUES ('ORDER_PAID', 'ORDER', ?, ?, 10)", orderId,
            "{\"order_id\":" + orderId + ",\"user_id\":501}");

        outboxProcessor.processOutbox();

        Integer eventStatus = jdbc.queryForObject(
            "SELECT status FROM outbox_events WHERE aggregate_id = ?",
            Integer.class, orderId);
        Integer taskCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM fulfillment_tasks WHERE order_id = ?",
            Integer.class, orderId);
        Integer orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?",
            Integer.class, orderId);

        assertEquals(30, eventStatus);
        assertEquals(1, taskCount);
        assertEquals(OrderStatus.PENDING_FULFILLMENT.getCode(), orderStatus);
    }

    private long seedPaidOrder() {
        String orderNo = "ORD" + System.nanoTime();
        jdbc.update("INSERT INTO orders (order_no, user_id, status, total_amount, paid_amount, remark, expires_at, version) " +
            "VALUES (?, 501, ?, 89.00, 89.00, 'outbox test', NOW() + INTERVAL '30' MINUTE, 0)",
            orderNo, OrderStatus.PAID.getCode());
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }
}
