package com.minidb.order.service;

import com.minidb.order.BusinessException;
import com.minidb.order.dto.CreateOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "minidb.order.proxy-mode=true",
        "spring.datasource.url=jdbc:h2:mem:minidb_proxy;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
@Transactional
class OrderServiceProxyModeTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ConsoleService consoleService;

    @Autowired
    private JdbcTemplate jdbc;

    private final long userId = 501L;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM fulfillment_tasks");
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM order_status_logs");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM inventory_journals");
        jdbc.execute("UPDATE product_inventory SET available_qty=100, locked_qty=0, shipped_qty=0 WHERE product_id IN (1001,1002,1003)");
    }

    @Test
    void shouldRequireUserIdForOrderDetailInProxyMode() {
        createTestOrder("proxy-detail-key-1");
        long orderId = orderService.listOrders(userId, null, 1, 1).items().get(0).orderId();

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.getOrder(orderId));
        assertEquals("SHARD_KEY_REQUIRED", ex.getErrorCode());

        var detail = orderService.getOrder(orderId, userId);
        assertEquals(userId, detail.userId());
        assertFalse(detail.items().isEmpty());
        assertFalse(detail.statusTimeline().isEmpty());
    }

    @Test
    void shouldRejectConsoleWideAggregationInProxyMode() {
        BusinessException ex = assertThrows(BusinessException.class, () -> consoleService.dashboardSummary());
        assertEquals("PROXY_MODE_UNSUPPORTED_QUERY", ex.getErrorCode());
    }

    private void createTestOrder(String idempotencyKey) {
        var req = new CreateOrderRequest(userId,
                List.of(new CreateOrderRequest.OrderItemRequest(1001L, 1)), null);
        orderService.createOrder(req, idempotencyKey);
    }
}
