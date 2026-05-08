package com.minidb.order.service;

import com.minidb.order.domain.OrderStatus;
import com.minidb.order.dto.CreateOrderRequest;
import com.minidb.order.dto.CreateOrderResponse;
import com.minidb.order.infra.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final int paymentTimeoutMinutes;

    public OrderService(JdbcTemplate jdbc, InventoryService inventoryService,
                        IdempotencyService idempotencyService, ObjectMapper objectMapper,
                        @Value("${minidb.order.payment-timeout-minutes:30}") int paymentTimeoutMinutes) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.paymentTimeoutMinutes = paymentTimeoutMinutes;
    }

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest req, String idempotencyKey) {
        log.info("Creating order for user={}, items={}", req.userId(), req.items().size());

        String reqJson;
        try { reqJson = objectMapper.writeValueAsString(req); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize request"); }

        String cached = idempotencyService.tryAcquire(idempotencyKey, "USER", req.userId(), reqJson);
        if (cached != null) {
            try { return objectMapper.readValue(cached, CreateOrderResponse.class); }
            catch (Exception e) { throw new BusinessException("DESERIALIZATION_ERROR", "Failed to deserialize cached response"); }
        }

        List<Long> productIds = req.items().stream().map(i -> i.productId()).toList();
        var products = jdbc.query(
            "SELECT id, sku, name, price, status FROM products WHERE id IN (" +
            productIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")",
            (rs, rowNum) -> new ProductSnapshot(rs.getLong("id"), rs.getString("sku"),
                rs.getString("name"), rs.getBigDecimal("price"), rs.getInt("status")),
            productIds.toArray()
        );
        if (products.size() != productIds.size()) throw new BusinessException("PRODUCT_NOT_FOUND", "Some products not found");
        for (var p : products) if (p.status != 1) throw new BusinessException("PRODUCT_OFFLINE", "Product offline: " + p.sku);

        String orderNo = generateOrderNo();
        List<InventoryService.StockLockItem> lockItems = new ArrayList<>();
        for (var item : req.items()) lockItems.add(new InventoryService.StockLockItem(item.productId(), item.quantity(), orderNo));
        inventoryService.lockBatch(lockItems);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < req.items().size(); i++) {
            final int idx = i;
            var prod = products.stream().filter(p -> p.id == req.items().get(idx).productId()).findFirst().orElseThrow();
            totalAmount = totalAmount.add(prod.price.multiply(BigDecimal.valueOf(req.items().get(idx).quantity())));
        }
        final BigDecimal finalAmount = totalAmount;
        LocalDateTime now = LocalDateTime.now();

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (order_no, user_id, status, total_amount, remark, expires_at, version) VALUES (?,?,?,?,?,?,0)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo); ps.setLong(2, req.userId());
            ps.setInt(3, OrderStatus.PENDING_PAYMENT.getCode()); ps.setBigDecimal(4, finalAmount);
            ps.setString(5, req.remark()); ps.setObject(6, now.plusMinutes(paymentTimeoutMinutes));
            return ps;
        }, keyHolder);
        long orderId = keyHolder.getKey().longValue();

        for (int i = 0; i < req.items().size(); i++) {
            var item = req.items().get(i);
            var prod = products.stream().filter(p -> p.id == item.productId()).findFirst().orElseThrow();
            jdbc.update("INSERT INTO order_items (user_id,order_id,product_id,product_sku,product_name,unit_price,quantity,line_amount) VALUES (?,?,?,?,?,?,?,?)",
                req.userId(), orderId, prod.id, prod.sku, prod.name, prod.price, item.quantity(),
                prod.price.multiply(BigDecimal.valueOf(item.quantity())));
        }

        writeStatusLog(orderId, orderNo, null, OrderStatus.PENDING_PAYMENT, "SYSTEM", "Order created");
        writeOutbox("ORDER_CREATED", "ORDER", orderId, buildPayload(orderNo, null));

        CreateOrderResponse response = new CreateOrderResponse(orderId, orderNo, OrderStatus.PENDING_PAYMENT.getCode(), finalAmount, now.plusMinutes(paymentTimeoutMinutes));
        String respJson;
        try { respJson = objectMapper.writeValueAsString(response); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize response"); }
        idempotencyService.markCompleted(idempotencyKey, "USER", req.userId(), respJson);
        log.info("Order created: orderNo={}, amount={}", orderNo, finalAmount);
        return response;
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason, String idempotencyKey, Long operatorId) {
        String reqJson;
        try { reqJson = objectMapper.writeValueAsString(Map.of("orderId", orderId, "reason", reason)); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize cancel request"); }
        String cached = idempotencyService.tryAcquire(idempotencyKey, "USER", operatorId, reqJson);
        if (cached != null) return;

        var order = jdbc.query("SELECT order_no, user_id, status, version FROM orders WHERE id = ?",
            rs -> { if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
                return new Object[]{rs.getString("order_no"), rs.getLong("user_id"), rs.getInt("status"), rs.getInt("version")}; },
            orderId);
        String orderNo = (String) order[0];
        int currentStatus = (int) order[2];

        if (currentStatus == OrderStatus.PENDING_PAYMENT.getCode()) {
            var items = jdbc.query("SELECT product_id, quantity FROM order_items WHERE order_id = ?",
                (rs, rn) -> new InventoryService.StockLockItem(rs.getLong("product_id"), rs.getInt("quantity"), orderNo), orderId);
            inventoryService.releaseBatch(items);
            int affected = jdbc.update("UPDATE orders SET status=?, cancelled_at=NOW(), version=version+1 WHERE id=? AND status=?",
                OrderStatus.CANCELLED.getCode(), orderId, OrderStatus.PENDING_PAYMENT.getCode());
            if (affected == 0) throw new BusinessException("ORDER_STATUS_CHANGED", "Order status already changed");
            writeStatusLog(orderId, orderNo, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "USER", reason);
            writeOutbox("ORDER_CANCELLED", "ORDER", orderId, buildPayload(orderNo, reason));
        } else if (currentStatus == OrderStatus.PAID.getCode()) {
            var items = jdbc.query("SELECT product_id, quantity FROM order_items WHERE order_id = ?",
                (rs, rn) -> new InventoryService.StockLockItem(rs.getLong("product_id"), rs.getInt("quantity"), orderNo), orderId);
            inventoryService.releaseBatch(items);
            int affected = jdbc.update("UPDATE orders SET status=?, version=version+1 WHERE id=? AND status=?",
                OrderStatus.REFUNDING.getCode(), orderId, OrderStatus.PAID.getCode());
            if (affected == 0) throw new BusinessException("ORDER_STATUS_CHANGED", "Order status already changed");
            writeStatusLog(orderId, orderNo, OrderStatus.PAID, OrderStatus.REFUNDING, "USER", reason);
            writeOutbox("ORDER_CANCEL_REFUND", "ORDER", orderId, buildPayload(orderNo, reason));
        } else if (currentStatus == OrderStatus.SHIPPED.getCode()) {
            throw new BusinessException("ORDER_STATUS_CHANGED", "Cannot cancel shipped order");
        } else {
            throw new BusinessException("ORDER_STATUS_CHANGED", "Cannot cancel order in status: " + currentStatus);
        }
        idempotencyService.markCompleted(idempotencyKey, "USER", operatorId, "{}");
        log.info("Order cancelled: orderNo={}, reason={}", orderNo, reason);
    }

    private String generateOrderNo() {
        return "ORD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", (int)(Math.random()*10000));
    }

    private void writeStatusLog(long orderId, String orderNo, OrderStatus from, OrderStatus to, String operator, String reason) {
        jdbc.update("INSERT INTO order_status_logs (order_id,order_no,from_status,to_status,operator,reason) VALUES (?,?,?,?,?,?)",
            orderId, orderNo, from != null ? from.getCode() : null, to.getCode(), operator, reason);
    }

    private String buildPayload(String orderNo, String reason) {
        try {
            Map<String, String> p = new HashMap<>();
            p.put("order_no", orderNo);
            if (reason != null) p.put("reason", reason);
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize outbox payload"); }
    }

    public void writeOutbox(String eventType, String aggregateType, long aggregateId, String payload) {
        jdbc.update("INSERT INTO outbox_events (event_type,aggregate_type,aggregate_id,payload,status) VALUES (?,?,?,?,10)",
            eventType, aggregateType, aggregateId, payload);
    }

    private record ProductSnapshot(long id, String sku, String name, BigDecimal price, int status) {}
}
