package com.minidb.order.service;

import com.minidb.order.OrderStatus;
import com.minidb.order.dto.PaymentCallbackRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentServiceTest {

    @Autowired private PaymentService paymentService;
    @Autowired private JdbcTemplate jdbc;
    @Value("${minidb.payment.mock-sign-secret}") private String mockSignSecret;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM exception_tickets");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM idempotency_records");
    }

    @Test
    void shouldReturnSamePaymentForDuplicateIdempotentCreate() {
        long orderId = seedPendingPaymentOrder(601L);

        String first = paymentService.createPayment(orderId, 601L, "mock_pay", "pay-key-1");
        String second = paymentService.createPayment(orderId, 601L, "mock_pay", "pay-key-1");

        Integer paymentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM payments WHERE order_id = ?",
            Integer.class, orderId);
        assertEquals(first, second);
        assertEquals(1, paymentCount);
    }

    @Test
    void shouldNotCreateDuplicateExceptionForRepeatedAmountMismatchCallback() {
        long orderId = seedPendingPaymentOrder(602L);
        String paymentNo = paymentService.createPayment(orderId, 602L, "mock_pay");
        LocalDateTime paidAt = LocalDateTime.now();
        BigDecimal wrongAmount = new BigDecimal("1.00");
        var callback = new PaymentCallbackRequest(
            paymentNo,
            "trade-" + paymentNo,
            wrongAmount,
            paidAt,
            "SUCCESS",
            sign(paymentNo, wrongAmount, "SUCCESS", paidAt)
        );

        paymentService.handleCallback(callback);
        paymentService.handleCallback(callback);

        Integer exceptionCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exception_tickets WHERE biz_no = ? AND reason_code = 'PAYMENT_AMOUNT_MISMATCH'",
            Integer.class, paymentNo);
        assertEquals(1, exceptionCount);
    }

    private long seedPendingPaymentOrder(long userId) {
        String orderNo = "ORD" + System.nanoTime();
        jdbc.update("INSERT INTO orders (order_no, user_id, status, total_amount, remark, expires_at, version) " +
            "VALUES (?, ?, ?, 89.00, 'payment test', NOW() + INTERVAL '30' MINUTE, 0)",
            orderNo, userId, OrderStatus.PENDING_PAYMENT.getCode());
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }

    private String sign(String paymentNo, BigDecimal amount, String status, LocalDateTime paidAt) {
        try {
            String content = paymentNo + "|" + amount + "|" + status + "|" + paidAt;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mockSignSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign callback", e);
        }
    }
}
