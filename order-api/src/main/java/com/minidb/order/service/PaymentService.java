package com.minidb.order.service;

import com.minidb.order.OrderStatus;
import com.minidb.order.PaymentStatus;
import com.minidb.order.dto.PaymentCallbackRequest;
import com.minidb.order.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final JdbcTemplate jdbc;
    private final OrderService orderService;
    private final String mockSignSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentService(JdbcTemplate jdbc, OrderService orderService,
                          @Value("${minidb.payment.mock-sign-secret}") String mockSignSecret) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.mockSignSecret = mockSignSecret;
    }

    @Transactional
    public String createPayment(Long orderId, Long userId, String channel) {
        var order = jdbc.query(
            "SELECT order_no, status, total_amount FROM orders WHERE id = ? AND user_id = ?",
            rs -> { if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found");
                return new Object[]{rs.getString("order_no"), rs.getInt("status"), rs.getBigDecimal("total_amount")}; },
            orderId, userId);
        if ((int) order[1] != OrderStatus.PENDING_PAYMENT.getCode())
            throw new BusinessException("ORDER_STATUS_CHANGED", "Order must be PENDING_PAYMENT");

        String paymentNo = "PAY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", (int)(Math.random()*10000));
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO payments (user_id,payment_no,order_id,channel,amount,status,version) VALUES (?,?,?,?,?,?,0)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId); ps.setString(2, paymentNo); ps.setLong(3, orderId); ps.setString(4, channel);
            ps.setBigDecimal(5, (BigDecimal) order[2]); ps.setInt(6, PaymentStatus.PENDING.getCode());
            return ps;
        }, kh);
        log.info("Payment created: paymentNo={}, orderId={}", paymentNo, orderId);
        bindPaymentRoute(paymentNo, (String) order[0]);
        return paymentNo;
    }

    @Transactional
    public void handleCallback(PaymentCallbackRequest req) {
        log.info("Payment callback: paymentNo={}, amount={}", req.paymentNo(), req.amount());
        if (!verifySignature(req)) throw new BusinessException("PAYMENT_SIGNATURE_INVALID", "Invalid payment signature");

        // Split query for sharding: first find payment (proxy does route lookup via payment_no)
        var paymentInfo = jdbc.query(
            "SELECT id, order_id, user_id, amount, status FROM payments WHERE payment_no = ?",
            rs -> { if (!rs.next()) throw new BusinessException("PAYMENT_NOT_FOUND", "Payment not found");
                return new Object[]{rs.getLong("id"), rs.getLong("order_id"),
                    rs.getLong("user_id"), rs.getBigDecimal("amount"), rs.getInt("status")}; },
            req.paymentNo());
        long paymentId = (long) paymentInfo[0]; long orderId = (long) paymentInfo[1];
        long userId = (long) paymentInfo[2];
        BigDecimal expected = (BigDecimal) paymentInfo[3]; int pStatus = (int) paymentInfo[4];

        // Then find order on same shard (both sharded by user_id)
        var orderInfo = jdbc.query(
            "SELECT order_no, status FROM orders WHERE id = ? AND user_id = ?",
            rs -> { if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found for payment");
                return new Object[]{rs.getString("order_no"), rs.getInt("status")}; },
            orderId, userId);
        String orderNo = (String) orderInfo[0];

        if (pStatus == PaymentStatus.SUCCESS.getCode()) return;

        if (req.amount().compareTo(expected) != 0) {
            jdbc.update("UPDATE payments SET status=?, raw_callback=? WHERE id=? AND status=?",
                PaymentStatus.EXCEPTION.getCode(), toJson(req), paymentId, PaymentStatus.PENDING.getCode());
            writeException("PAYMENT", req.paymentNo(), "PAYMENT_AMOUNT_MISMATCH",
                "Expected " + expected + ", received " + req.amount());
            return;
        }

        int pUpd = jdbc.update("UPDATE payments SET status=?, paid_at=?, channel_trade_no=?, raw_callback=?, version=version+1 WHERE id=? AND status=?",
            PaymentStatus.SUCCESS.getCode(), req.paidAt(), req.channelTradeNo(), toJson(req), paymentId, PaymentStatus.PENDING.getCode());
        if (pUpd == 0) throw new BusinessException("PAYMENT_STATUS_CHANGED", "Payment status already changed");

        int oUpd = jdbc.update("UPDATE orders SET status=?, paid_amount=?, paid_at=?, version=version+1 WHERE id=? AND status=?",
            OrderStatus.PAID.getCode(), req.amount(), req.paidAt(), orderId, OrderStatus.PENDING_PAYMENT.getCode());
        if (oUpd == 0) {
            var cur = jdbc.query("SELECT status FROM orders WHERE id=?", rs -> rs.next() ? rs.getInt("status") : null, orderId);
            if (cur != null && cur == OrderStatus.CANCELLED.getCode()) {
                writeException("PAYMENT", req.paymentNo(), "ORDER_ALREADY_CANCELLED", "Payment succeeded but order cancelled");
                jdbc.update("UPDATE payments SET status=? WHERE id=?", PaymentStatus.REFUNDING.getCode(), paymentId);
            }
            return;
        }

        jdbc.update("INSERT INTO order_status_logs (order_id,order_no,from_status,to_status,operator,reason) VALUES (?,?,?,?,'SYSTEM','Payment callback')",
            orderId, orderNo, OrderStatus.PENDING_PAYMENT.getCode(), OrderStatus.PAID.getCode());

        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("order_no", orderNo);
            payload.put("payment_no", req.paymentNo());
            orderService.writeOutbox("ORDER_PAID", "ORDER", orderId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) { log.error("Failed to write outbox", e); }
        log.info("Payment callback processed: paymentNo={}", req.paymentNo());
    }

    private boolean verifySignature(PaymentCallbackRequest req) {
        String content = req.paymentNo() + "|" + req.amount() + "|" + req.status() + "|" + req.paidAt();
        String expected = hmacSha256(content, mockSignSecret);
        return expected.equals(req.signature());
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) { throw new RuntimeException("HMAC-SHA256 error", e); }
    }

    private void bindPaymentRoute(String paymentNo, String orderNo) {
        try {
            jdbc.update("UPDATE order_route SET payment_no = ?, biz_type = 'PAYMENT', updated_at = NOW() WHERE order_no = ?",
                    paymentNo, orderNo);
        } catch (Exception e) {
            log.warn("Failed to bind payment route: paymentNo={}, orderNo={}: {}", paymentNo, orderNo, e.getMessage());
        }
    }

    private void writeException(String bizType, String bizNo, String reasonCode, String detail) {
        jdbc.update("INSERT INTO exception_tickets (biz_type,biz_no,reason_code,detail,status) VALUES (?,?,?,?,10)",
            bizType, bizNo, reasonCode, detail);
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
