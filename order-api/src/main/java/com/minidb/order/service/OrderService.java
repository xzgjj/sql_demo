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
                new String[]{"id"});
            ps.setString(1, orderNo); ps.setLong(2, req.userId());
            ps.setInt(3, OrderStatus.PENDING_PAYMENT.getCode()); ps.setBigDecimal(4, finalAmount);
            ps.setString(5, req.remark()); ps.setObject(6, now.plusMinutes(paymentTimeoutMinutes));
            return ps;
        }, keyHolder);
        long orderId = keyHolder.getKeyAs(Long.class);

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

    // ---- Query methods ----

    public OrderListPage listOrders(long userId, Integer status, int page, int pageSize) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();
        conditions.add("user_id = ?");
        params.add(userId);
        if (status != null) {
            conditions.add("status = ?");
            params.add(status);
        }
        String whereClause = String.join(" AND ", conditions);

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE " + whereClause,
            Long.class, params.toArray()
        );

        int offset = (page - 1) * pageSize;
        var queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        var items = jdbc.query(
            "SELECT o.id, o.order_no, o.status, o.total_amount, o.created_at, " +
            "(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id) AS item_count " +
            "FROM orders o WHERE " + whereClause + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> new OrderSummary(
                rs.getLong("id"), rs.getString("order_no"), rs.getInt("status"),
                rs.getBigDecimal("total_amount"), rs.getInt("item_count"),
                rs.getTimestamp("created_at").toLocalDateTime()
            ),
            queryParams.toArray()
        );

        return new OrderListPage(items, page, pageSize, total != null ? total : 0);
    }

    public OrderDetail getOrder(long orderId) {
        var rows = jdbc.query(
            "SELECT id, order_no, user_id, status, total_amount, paid_amount, remark, " +
            "expires_at, paid_at, cancelled_at, completed_at, created_at " +
            "FROM orders WHERE id = ?",
            (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("user_id"), rs.getInt("status"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"), rs.getString("remark"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("paid_at"),
                rs.getTimestamp("cancelled_at"), rs.getTimestamp("completed_at"),
                rs.getTimestamp("created_at")),
            orderId
        );
        if (rows.isEmpty()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
        return buildDetail(rows.get(0));
    }

    public OrderDetail getOrderByNo(String orderNo) {
        var rows = jdbc.query(
            "SELECT id, order_no, user_id, status, total_amount, paid_amount, remark, " +
            "expires_at, paid_at, cancelled_at, completed_at, created_at " +
            "FROM orders WHERE order_no = ?",
            (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("user_id"), rs.getInt("status"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"), rs.getString("remark"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("paid_at"),
                rs.getTimestamp("cancelled_at"), rs.getTimestamp("completed_at"),
                rs.getTimestamp("created_at")),
            orderNo
        );
        if (rows.isEmpty()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNo);
        return buildDetail(rows.get(0));
    }

    private OrderDetail buildDetail(OrderRow r) {
        long orderId = r.id; String orderNo = r.orderNo;
        long userId = r.userId; int status = r.status;

        var items = jdbc.query(
            "SELECT product_id, product_sku, product_name, unit_price, quantity, line_amount " +
            "FROM order_items WHERE order_id = ?",
            (rs, rn) -> new OrderItemInfo(rs.getLong("product_id"), rs.getString("product_sku"),
                rs.getString("product_name"), rs.getBigDecimal("unit_price"),
                rs.getInt("quantity"), rs.getBigDecimal("line_amount")),
            orderId
        );

        var payments = jdbc.query(
            "SELECT payment_no, channel, amount, status, paid_at FROM payments WHERE order_id = ? LIMIT 1",
            (rs) -> rs.next() ? new PaymentInfo(rs.getString("payment_no"), rs.getString("channel"),
                rs.getBigDecimal("amount"), rs.getInt("status"),
                rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null) : null,
            orderId
        );

        var fulfillments = jdbc.query(
            "SELECT ft.task_no, ft.status, ft.assignee_id, ft.claimed_at, ft.shipped_at, " +
            "s.carrier, s.tracking_no FROM fulfillment_tasks ft " +
            "LEFT JOIN shipments s ON s.task_id = ft.id WHERE ft.order_id = ? LIMIT 1",
            (rs) -> rs.next() ? new FulfillmentInfo(rs.getString("task_no"), rs.getInt("status"),
                (Long) rs.getObject("assignee_id"),
                rs.getTimestamp("claimed_at") != null ? rs.getTimestamp("claimed_at").toLocalDateTime() : null,
                rs.getTimestamp("shipped_at") != null ? rs.getTimestamp("shipped_at").toLocalDateTime() : null,
                rs.getString("carrier"), rs.getString("tracking_no")) : null,
            orderId
        );

        var timeline = jdbc.query(
            "SELECT from_status, to_status, operator, reason, created_at " +
            "FROM order_status_logs WHERE order_id = ? ORDER BY created_at",
            (rs, rn) -> new StatusLogEntry(
                (Integer) rs.getObject("from_status"), rs.getInt("to_status"),
                rs.getString("operator"), rs.getString("reason"),
                rs.getTimestamp("created_at").toLocalDateTime()),
            orderId
        );

        return new OrderDetail(orderId, orderNo, userId, status,
            r.totalAmount, r.paidAmount, r.remark,
            r.expiresAt != null ? r.expiresAt.toLocalDateTime() : null,
            r.paidAt != null ? r.paidAt.toLocalDateTime() : null,
            r.cancelledAt != null ? r.cancelledAt.toLocalDateTime() : null,
            r.completedAt != null ? r.completedAt.toLocalDateTime() : null,
            r.createdAt.toLocalDateTime(),
            items, payments, fulfillments, timeline);
    }

    // ---- DTOs ----

    public record OrderSummary(long orderId, String orderNo, int status,
                               java.math.BigDecimal totalAmount, int itemCount,
                               java.time.LocalDateTime createdAt) {}

    public record OrderDetail(long orderId, String orderNo, long userId, int status,
                              java.math.BigDecimal totalAmount, java.math.BigDecimal paidAmount,
                              String remark, java.time.LocalDateTime expiresAt,
                              java.time.LocalDateTime paidAt, java.time.LocalDateTime cancelledAt,
                              java.time.LocalDateTime completedAt, java.time.LocalDateTime createdAt,
                              java.util.List<OrderItemInfo> items, PaymentInfo payment,
                              FulfillmentInfo fulfillment,
                              java.util.List<StatusLogEntry> statusTimeline) {}

    public record OrderItemInfo(long productId, String productSku, String productName,
                                java.math.BigDecimal unitPrice, int quantity,
                                java.math.BigDecimal lineAmount) {}

    public record PaymentInfo(String paymentNo, String channel, java.math.BigDecimal amount,
                              int status, java.time.LocalDateTime paidAt) {}

    public record FulfillmentInfo(String taskNo, int status, Long assigneeId,
                                  java.time.LocalDateTime claimedAt,
                                  java.time.LocalDateTime shippedAt,
                                  String carrier, String trackingNo) {}

    public record StatusLogEntry(Integer fromStatus, int toStatus, String operator,
                                  String reason, java.time.LocalDateTime createdAt) {}

    public record OrderListPage(java.util.List<OrderSummary> items, int page, int pageSize, long total) {}

    private record OrderRow(long id, String orderNo, long userId, int status,
                              BigDecimal totalAmount, BigDecimal paidAmount, String remark,
                              java.sql.Timestamp expiresAt, java.sql.Timestamp paidAt,
                              java.sql.Timestamp cancelledAt, java.sql.Timestamp completedAt,
                              java.sql.Timestamp createdAt) {}

    private record ProductSnapshot(long id, String sku, String name, BigDecimal price, int status) {}
}
