package com.minidb.order.service;

import com.minidb.order.dto.CreateOrderRequest;
import com.minidb.order.infra.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderServiceQueryTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId = 501L;
    private String testOrderNo;

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
    void shouldListUserOrders() {
        createTestOrder("list-key-1");
        createTestOrder("list-key-2");

        var page = orderService.listOrders(userId, null, 1, 20);
        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
        for (var item : page.items()) {
            assertNotNull(item.orderNo());
            assertTrue(item.totalAmount().doubleValue() > 0);
        }
    }

    @Test
    void shouldFilterByStatus() {
        createTestOrder("filter-key-1");

        var paidPage = orderService.listOrders(userId, 20, 1, 20);
        assertEquals(0, paidPage.total(), "No paid orders yet");

        var pendingPage = orderService.listOrders(userId, 10, 1, 20);
        assertEquals(1, pendingPage.total(), "One pending payment order");
    }

    @Test
    void shouldReturnEmptyForNonExistentUser() {
        var page = orderService.listOrders(99999L, null, 1, 20);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void shouldGetOrderDetail() {
        createTestOrder("detail-key-1");

        var orders = orderService.listOrders(userId, null, 1, 1);
        long orderId = orders.items().get(0).orderId();

        var detail = orderService.getOrder(orderId);
        assertNotNull(detail.orderNo());
        assertEquals(userId, detail.userId());
        assertFalse(detail.items().isEmpty(), "Should have order items");
        assertFalse(detail.statusTimeline().isEmpty(), "Should have status timeline");
    }

    @Test
    void shouldGetOrderByNo() {
        createTestOrder("byno-key-1");

        var orders = orderService.listOrders(userId, null, 1, 1);
        String orderNo = orders.items().get(0).orderNo();

        var detail = orderService.getOrderByNo(orderNo);
        assertEquals(orderNo, detail.orderNo());
    }

    @Test
    void shouldThrowForNonExistentOrder() {
        assertThrows(BusinessException.class, () -> orderService.getOrder(99999L));
        assertThrows(BusinessException.class, () -> orderService.getOrderByNo("NONEXISTENT"));
    }

    private void createTestOrder(String idempotencyKey) {
        var req = new CreateOrderRequest(userId,
            List.of(new CreateOrderRequest.OrderItemRequest(1001L, 1)), null);
        var resp = orderService.createOrder(req, idempotencyKey);
        testOrderNo = resp.orderNo();
    }
}
