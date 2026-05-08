package com.minidb.order.service;

import com.minidb.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单过期处理器。
 * 每30秒扫描一次过期的待支付订单并自动取消。
 */
@Component
public class OrderExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private final JdbcTemplate jdbc;
    private final OrderService orderService;

    public OrderExpiryScheduler(JdbcTemplate jdbc, OrderService orderService) {
        this.jdbc = jdbc;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void cancelExpiredOrders() {
        var expired = jdbc.query(
            "SELECT id, order_no FROM orders WHERE status = ? AND expires_at < NOW() LIMIT 50",
            (rs, rowNum) -> new Object[]{rs.getLong("id"), rs.getString("order_no")},
            OrderStatus.PENDING_PAYMENT.getCode()
        );

        for (var row : expired) {
            long orderId = (long) row[0];
            String orderNo = (String) row[1];
            try {
                orderService.cancelOrder(orderId, "Payment timeout",
                    "expiry-" + orderId, 0L);
                log.info("Auto-cancelled expired order: {}", orderNo);
            } catch (Exception e) {
                log.error("Failed to cancel expired order {}: {}", orderNo, e.getMessage());
            }
        }
    }
}
