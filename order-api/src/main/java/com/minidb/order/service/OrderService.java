package com.minidb.order.service;

import com.minidb.order.OrderStatus;
import com.minidb.order.dto.CreateOrderRequest;
import com.minidb.order.dto.CreateOrderResponse;
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

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final int paymentTimeoutMinutes;
    private final boolean proxyMode;
    private final TwoPhaseCoordinator twoPhaseCoordinator;
    private final TraceService traceService;

    public OrderService(JdbcTemplate jdbc, InventoryService inventoryService,
                        IdempotencyService idempotencyService, ObjectMapper objectMapper,
                        @Value("${minidb.order.payment-timeout-minutes:30}") int paymentTimeoutMinutes,
                        @Value("${minidb.order.proxy-mode:false}") boolean proxyMode,
                        TwoPhaseCoordinator twoPhaseCoordinator,
                        TraceService traceService) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.paymentTimeoutMinutes = paymentTimeoutMinutes;
        this.proxyMode = proxyMode;
        this.twoPhaseCoordinator = twoPhaseCoordinator;
        this.traceService = traceService;
    }

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest req, String idempotencyKey) {
        log.info("Creating order for user={}, items={}, mode={}",
                req.userId(), req.items().size(), proxyMode ? "proxy/2PC" : "single-DB");

        String traceId = TraceContext.generate();
        String reqJson;
        try { reqJson = objectMapper.writeValueAsString(req); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize request"); }

        String cached = idempotencyService.tryAcquire(idempotencyKey, "USER", req.userId(), reqJson);
        if (cached != null) {
            try { return objectMapper.readValue(cached, CreateOrderResponse.class); }
            catch (Exception e) { throw new BusinessException("DESERIALIZATION_ERROR", "Failed to deserialize cached response"); }
        }

        try {
            long productStart = System.currentTimeMillis();
            List<Long> productIds = req.items().stream().map(i -> i.productId()).toList();
            var products = jdbc.query(
                "SELECT id, sku, name, price, status FROM products WHERE id IN (" +
                productIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")",
                (rs, rowNum) -> new ProductSnapshot(rs.getLong("id"), rs.getString("sku"),
                    rs.getString("name"), rs.getBigDecimal("price"), rs.getInt("status")),
                productIds.toArray()
            );
            traceService.recordSql(traceId, "SELECT products", null, req.userId(),
                    "NONE", "PRIMARY", null, "OK", null,
                    (int)(System.currentTimeMillis() - productStart));

            if (products.size() != productIds.size()) throw new BusinessException("PRODUCT_NOT_FOUND", "Some products not found");
            for (var p : products) if (p.status != 1) throw new BusinessException("PRODUCT_OFFLINE", "Product offline: " + p.sku);

            String orderNo = generateOrderNo();
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (int i = 0; i < req.items().size(); i++) {
                final int idx = i;
                var prod = products.stream().filter(p -> p.id == req.items().get(idx).productId()).findFirst().orElseThrow();
                totalAmount = totalAmount.add(prod.price.multiply(BigDecimal.valueOf(req.items().get(idx).quantity())));
            }
            final BigDecimal finalAmount = totalAmount;
            LocalDateTime now = LocalDateTime.now();

            if (proxyMode) {
                return completeCreateOrderVia2pc(req, idempotencyKey, traceId, orderNo,
                        products, finalAmount, now);
            }

            // ---- Single-DB path (original logic) ----
            List<InventoryService.StockLockItem> lockItems = new ArrayList<>();
            for (var item : req.items()) lockItems.add(new InventoryService.StockLockItem(item.productId(), item.quantity(), orderNo));
            inventoryService.lockBatch(lockItems);

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
            Long generatedOrderId = keyHolder.getKeyAs(Long.class);
            if (generatedOrderId == null) {
                throw new BusinessException("ORDER_ID_GENERATION_FAILED", "Failed to obtain generated order id");
            }
            long orderId = generatedOrderId;

            for (int i = 0; i < req.items().size(); i++) {
                var item = req.items().get(i);
                var prod = products.stream().filter(p -> p.id == item.productId()).findFirst().orElseThrow();
                jdbc.update("INSERT INTO order_items (user_id,order_id,product_id,product_sku,product_name,unit_price,quantity,line_amount) VALUES (?,?,?,?,?,?,?,?)",
                    req.userId(), orderId, prod.id, prod.sku, prod.name, prod.price, item.quantity(),
                    prod.price.multiply(BigDecimal.valueOf(item.quantity())));
            }

            writeStatusLog(orderId, orderNo, null, OrderStatus.PENDING_PAYMENT, "SYSTEM", "Order created");
            writeOutbox("ORDER_CREATED", "ORDER", orderId, buildPayload(orderNo, null));
            writeOrderRoute(orderNo, req.userId());

            CreateOrderResponse response = new CreateOrderResponse(orderId, orderNo,
                    OrderStatus.PENDING_PAYMENT.getCode(), finalAmount,
                    now.plusMinutes(paymentTimeoutMinutes));
            String respJson;
            try { respJson = objectMapper.writeValueAsString(response); }
            catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize response"); }
            idempotencyService.markCompleted(idempotencyKey, "USER", req.userId(), respJson);
            log.info("Order created (single-DB): orderNo={}, amount={}", orderNo, finalAmount);
            return response;
        } catch (RuntimeException e) {
            idempotencyService.markFailed(idempotencyKey, "USER", req.userId());
            throw e;
        }
    }

    /**
     * Proxy-mode order creation via 2PC.
     * Idempotency and product reads use proxy JdbcTemplate (single-datasource queries).
     * Data writes across PRIMARY + shard_N use the TwoPhaseCoordinator via direct JDBC.
     */
    private CreateOrderResponse completeCreateOrderVia2pc(
            CreateOrderRequest req, String idempotencyKey, String traceId,
            String orderNo, List<ProductSnapshot> products,
            BigDecimal finalAmount, LocalDateTime now) {

        long userId = req.userId();
        int shardIdx = twoPhaseCoordinator.shardIndex(userId);
        String expiresAt = now.plusMinutes(paymentTimeoutMinutes).toString();
        int pendingCode = OrderStatus.PENDING_PAYMENT.getCode();

        // Build PRIMARY participant statements
        List<TwoPhaseCoordinator.SqlStatement> primaryStmts = new ArrayList<>();
        // Lock inventory for each item
        for (var item : req.items()) {
            primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
                "UPDATE product_inventory SET available_qty = available_qty - ?, " +
                "locked_qty = locked_qty + ?, version = version + 1 " +
                "WHERE product_id = ? AND available_qty >= ?",
                item.quantity(), item.quantity(), item.productId(), item.quantity()));
            primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
                "INSERT INTO inventory_journals (product_id, biz_type, biz_no, " +
                "change_available, change_locked, change_shipped) VALUES (?, 'ORDER_CREATE', ?, ?, ?, 0)",
                item.productId(), orderNo, -item.quantity(), item.quantity()));
        }
        // Outbox
        primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload, status) " +
            "VALUES ('ORDER_CREATED', 'ORDER', 0, ?, 10)",
            buildPayload(orderNo, null)));
        // Route table
        primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "INSERT INTO order_route (order_no, user_id, biz_type) VALUES (?, ?, 'ORDER') " +
            "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), updated_at = NOW()",
            orderNo, userId));

        // Build shard participant statements
        List<TwoPhaseCoordinator.SqlStatement> shardStmts = new ArrayList<>();
        // Order header
        shardStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "INSERT INTO orders (order_no, user_id, status, total_amount, remark, expires_at, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, 0)",
            orderNo, userId, pendingCode, finalAmount, req.remark(), expiresAt));
        // Order items
        for (var item : req.items()) {
            var prod = products.stream().filter(p -> p.id == item.productId()).findFirst().orElseThrow();
            shardStmts.add(new TwoPhaseCoordinator.SqlStatement(
                "INSERT INTO order_items (user_id, order_id, product_id, product_sku, " +
                "product_name, unit_price, quantity, line_amount) " +
                "VALUES (?, (SELECT id FROM orders WHERE order_no = ?), ?, ?, ?, ?, ?, ?)",
                userId, orderNo, prod.id, prod.sku, prod.name, prod.price,
                item.quantity(), prod.price.multiply(BigDecimal.valueOf(item.quantity()))));
        }

        // Execute 2PC
        TwoPhaseCoordinator.TwoPhasePlan plan = new TwoPhaseCoordinator.TwoPhasePlan(
                traceId, shardIdx, primaryStmts, shardStmts,
                "createOrder " + orderNo + " user=" + userId);
        TwoPhaseCoordinator.TwoPhaseResult result = twoPhaseCoordinator.execute(plan);

        if (!result.success()) {
            String detail = result.votes().stream()
                    .map(v -> v.dataSourceName() + "=" + v.vote())
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            idempotencyService.markFailed(idempotencyKey, "USER", userId);
            throw new BusinessException("2PC_ABORTED",
                    "2PC createOrder failed: " + detail + ". Retry with same Idempotency-Key.");
        }

        // After 2PC success: query the generated order_id
        Long orderId = jdbc.query(
            "SELECT id FROM orders WHERE order_no = ? AND user_id = ?",
            rs -> rs.next() ? rs.getLong("id") : null,
            orderNo, userId);
        if (orderId == null) {
            idempotencyService.markFailed(idempotencyKey, "USER", userId);
            throw new BusinessException("ORDER_ID_GENERATION_FAILED",
                    "Order created but ID lookup failed — check 2PC trace: " + traceId);
        }

        // Write status log and finalize route (these are on PRIMARY via proxy)
        writeStatusLog(orderId, orderNo, null, OrderStatus.PENDING_PAYMENT, "SYSTEM",
                "Order created via 2PC");
        // Update outbox with correct aggregate_id
        jdbc.update("UPDATE outbox_events SET aggregate_id = ? WHERE aggregate_type = 'ORDER' AND aggregate_id = 0 AND payload LIKE ?",
                orderId, "%" + orderNo + "%");
        writeOrderRoute(orderNo, userId);

        CreateOrderResponse response = new CreateOrderResponse(orderId, orderNo,
                pendingCode, finalAmount, now.plusMinutes(paymentTimeoutMinutes));
        String respJson;
        try { respJson = objectMapper.writeValueAsString(response); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize response"); }
        idempotencyService.markCompleted(idempotencyKey, "USER", userId, respJson);

        log.info("Order created via 2PC: orderId={}, orderNo={}, traceId={}, elapsed={}ms",
                orderId, orderNo, traceId, result.elapsedMs());
        return response;
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason, String idempotencyKey, Long operatorId) {
        String traceId = TraceContext.generate();
        String reqJson;
        try { reqJson = objectMapper.writeValueAsString(Map.of("orderId", orderId, "reason", reason)); }
        catch (Exception e) { throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize cancel request"); }
        String cached = idempotencyService.tryAcquire(idempotencyKey, "USER", operatorId, reqJson);
        if (cached != null) return;

        try {
            // Read order info (via proxy in proxy mode, direct in single-DB)
            var order = Objects.requireNonNull(proxyMode
                ? jdbc.query("SELECT order_no, user_id, status, version FROM orders WHERE id = ? AND user_id = ?",
                    rs -> { if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
                        return new Object[]{rs.getString("order_no"), rs.getLong("user_id"), rs.getInt("status"), rs.getInt("version")}; },
                    orderId, operatorId)
                : jdbc.query("SELECT order_no, user_id, status, version FROM orders WHERE id = ?",
                rs -> { if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
                    return new Object[]{rs.getString("order_no"), rs.getLong("user_id"), rs.getInt("status"), rs.getInt("version")}; },
                orderId));
            String orderNo = (String) order[0];
            long userId = (long) order[1];
            int currentStatus = (int) order[2];

            // Validate cancellable states
            if (currentStatus == OrderStatus.SHIPPED.getCode()) {
                throw new BusinessException("ORDER_STATUS_CHANGED", "Cannot cancel shipped order");
            }
            if (currentStatus != OrderStatus.PENDING_PAYMENT.getCode()
                    && currentStatus != OrderStatus.PAID.getCode()) {
                throw new BusinessException("ORDER_STATUS_CHANGED",
                        "Cannot cancel order in status: " + currentStatus);
            }

            // Read items (needed for inventory release)
            var items = proxyMode
                ? jdbc.query("SELECT product_id, quantity FROM order_items WHERE order_id = ? AND user_id = ?",
                    (rs, rn) -> new InventoryService.StockLockItem(rs.getLong("product_id"), rs.getInt("quantity"), orderNo),
                    orderId, userId)
                : jdbc.query("SELECT product_id, quantity FROM order_items WHERE order_id = ?",
                    (rs, rn) -> new InventoryService.StockLockItem(rs.getLong("product_id"), rs.getInt("quantity"), orderNo),
                    orderId);

            if (proxyMode) {
                completeCancelOrderVia2pc(orderId, orderNo, userId, currentStatus, reason,
                        idempotencyKey, operatorId, traceId, items);
                return;
            }

            // ---- Single-DB path ----
            if (currentStatus == OrderStatus.PENDING_PAYMENT.getCode()) {
                inventoryService.releaseBatch(items);
                int affected = jdbc.update("UPDATE orders SET status=?, cancelled_at=NOW(), version=version+1 WHERE id=? AND user_id=? AND status=?",
                    OrderStatus.CANCELLED.getCode(), orderId, userId, OrderStatus.PENDING_PAYMENT.getCode());
                if (affected == 0) throw new BusinessException("ORDER_STATUS_CHANGED", "Order status already changed");
                writeStatusLog(orderId, orderNo, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "USER", reason);
                writeOutbox("ORDER_CANCELLED", "ORDER", orderId, buildPayload(orderNo, reason));
            } else {
                inventoryService.releaseBatch(items);
                int affected = jdbc.update("UPDATE orders SET status=?, version=version+1 WHERE id=? AND user_id=? AND status=?",
                    OrderStatus.REFUNDING.getCode(), orderId, userId, OrderStatus.PAID.getCode());
                if (affected == 0) throw new BusinessException("ORDER_STATUS_CHANGED", "Order status already changed");
                writeStatusLog(orderId, orderNo, OrderStatus.PAID, OrderStatus.REFUNDING, "USER", reason);
                writeOutbox("ORDER_CANCEL_REFUND", "ORDER", orderId, buildPayload(orderNo, reason));
            }
            idempotencyService.markCompleted(idempotencyKey, "USER", operatorId, "{}");
            log.info("Order cancelled (single-DB): orderNo={}, reason={}", orderNo, reason);
        } catch (RuntimeException e) {
            idempotencyService.markFailed(idempotencyKey, "USER", operatorId);
            throw e;
        }
    }

    private void completeCancelOrderVia2pc(long orderId, String orderNo, long userId,
                                           int currentStatus, String reason,
                                           String idempotencyKey, Long operatorId,
                                           String traceId,
                                           List<InventoryService.StockLockItem> items) {
        int shardIdx = twoPhaseCoordinator.shardIndex(userId);
        int newStatus = (currentStatus == OrderStatus.PENDING_PAYMENT.getCode())
                ? OrderStatus.CANCELLED.getCode()
                : OrderStatus.REFUNDING.getCode();
        String eventType = (currentStatus == OrderStatus.PENDING_PAYMENT.getCode())
                ? "ORDER_CANCELLED" : "ORDER_CANCEL_REFUND";

        // PRIMARY participant: release inventory + journals + status log + outbox
        List<TwoPhaseCoordinator.SqlStatement> primaryStmts = new ArrayList<>();
        for (var item : items) {
            primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
                "UPDATE product_inventory SET available_qty = available_qty + ?, " +
                "locked_qty = locked_qty - ?, version = version + 1 " +
                "WHERE product_id = ? AND locked_qty >= ?",
                item.quantity(), item.quantity(), item.productId(), item.quantity()));
            primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
                "INSERT INTO inventory_journals (product_id, biz_type, biz_no, " +
                "change_available, change_locked, change_shipped) " +
                "VALUES (?, 'ORDER_CANCEL', ?, ?, ?, 0)",
                item.productId(), orderNo, item.quantity(), -item.quantity()));
        }
        primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "INSERT INTO order_status_logs (order_id, order_no, from_status, to_status, operator, reason) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            orderId, orderNo, currentStatus, newStatus, "USER", reason));
        primaryStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload, status) " +
            "VALUES (?, 'ORDER', ?, ?, 10)",
            eventType, orderId, buildPayload(orderNo, reason)));

        // Shard participant: update order status (conditional on CURRENT status)
        List<TwoPhaseCoordinator.SqlStatement> shardStmts = new ArrayList<>();
        shardStmts.add(new TwoPhaseCoordinator.SqlStatement(
            "UPDATE orders SET status = ?, cancelled_at = NOW(), version = version + 1 " +
            "WHERE id = ? AND user_id = ? AND status = ?",
            newStatus, orderId, userId, currentStatus));

        TwoPhaseCoordinator.TwoPhasePlan plan = new TwoPhaseCoordinator.TwoPhasePlan(
                traceId, shardIdx, primaryStmts, shardStmts,
                "cancelOrder " + orderNo + " user=" + userId + " status=" + currentStatus);
        TwoPhaseCoordinator.TwoPhaseResult result = twoPhaseCoordinator.execute(plan);

        if (!result.success()) {
            idempotencyService.markFailed(idempotencyKey, "USER", operatorId);
            String detail = result.votes().stream()
                    .map(v -> v.dataSourceName() + "=" + v.vote())
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            throw new BusinessException("2PC_ABORTED",
                    "2PC cancelOrder failed: " + detail + ". Check order status and retry.");
        }

        // Verify the shard update actually took effect
        Integer postStatus = jdbc.query(
            "SELECT status FROM orders WHERE id = ? AND user_id = ?",
            rs -> rs.next() ? rs.getInt("status") : null,
            orderId, userId);
        if (postStatus == null || postStatus == currentStatus) {
            // Status didn't change — someone else modified it between our read and 2PC
            // The inventory release already happened (PRIMARY committed). Write exception.
            log.warn("Cancel 2PC committed but order status unchanged: orderNo={}, expected={}, actual={}",
                    orderNo, newStatus, postStatus);
            writeException("ORDER", orderNo, "ORDER_STATUS_RACE",
                    "Cancel committed but status unchanged (race condition). Expected status "
                    + newStatus + ", actual " + postStatus + ". Inventory may need manual adjustment.");
            idempotencyService.markFailed(idempotencyKey, "USER", operatorId);
            throw new BusinessException("ORDER_STATUS_CHANGED",
                    "Order status was modified concurrently. Please refresh and retry.");
        }

        idempotencyService.markCompleted(idempotencyKey, "USER", operatorId, "{}");
        log.info("Order cancelled via 2PC: orderNo={}, newStatus={}, traceId={}, elapsed={}ms",
                orderNo, newStatus, traceId, result.elapsedMs());
    }

    private void writeException(String bizType, String bizNo, String reasonCode, String detail) {
        try {
            jdbc.update("INSERT INTO exception_tickets (biz_type, biz_no, reason_code, detail, status) " +
                    "VALUES (?, ?, ?, ?, 10)", bizType, bizNo, reasonCode,
                    toJson(Map.of("detail", detail)));
        } catch (Exception e) {
            log.error("Failed to write exception ticket for {} {}: {}", bizType, bizNo, e.getMessage());
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
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

    private void writeOrderRoute(String orderNo, long userId) {
        try {
            jdbc.update("INSERT INTO order_route (order_no, user_id, biz_type) VALUES (?, ?, 'ORDER') " +
                        "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), updated_at = NOW()",
                    orderNo, userId);
        } catch (Exception e) {
            log.warn("Failed to write order_route for orderNo={}: {}", orderNo, e.getMessage());
        }
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
        return getOrder(orderId, null);
    }

    public OrderDetail getOrder(long orderId, Long userId) {
        if (proxyMode && userId == null) {
            throw new BusinessException("SHARD_KEY_REQUIRED",
                    "X-User-Id is required when querying order details through mini-proxy");
        }
        var rows = userId == null ? jdbc.query(
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
        ) : jdbc.query(
            "SELECT id, order_no, user_id, status, total_amount, paid_amount, remark, " +
            "expires_at, paid_at, cancelled_at, completed_at, created_at " +
            "FROM orders WHERE id = ? AND user_id = ?",
            (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("user_id"), rs.getInt("status"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"), rs.getString("remark"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("paid_at"),
                rs.getTimestamp("cancelled_at"), rs.getTimestamp("completed_at"),
                rs.getTimestamp("created_at")),
            orderId, userId
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
            "FROM order_items WHERE order_id = ? AND user_id = ?",
            (rs, rn) -> new OrderItemInfo(rs.getLong("product_id"), rs.getString("product_sku"),
                rs.getString("product_name"), rs.getBigDecimal("unit_price"),
                rs.getInt("quantity"), rs.getBigDecimal("line_amount")),
            orderId, userId
        );

        var payments = jdbc.query(
            "SELECT payment_no, channel, amount, status, paid_at FROM payments WHERE order_id = ? AND user_id = ? LIMIT 1",
            (rs) -> rs.next() ? new PaymentInfo(rs.getString("payment_no"), rs.getString("channel"),
                rs.getBigDecimal("amount"), rs.getInt("status"),
                rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null) : null,
            orderId, userId
        );

        var fulfillments = jdbc.query(
            "SELECT ft.task_no, ft.status, ft.assignee_id, ft.claimed_at, ft.shipped_at, " +
            "s.carrier, s.tracking_no FROM fulfillment_tasks ft " +
            "LEFT JOIN shipments s ON s.task_id = ft.id WHERE ft.order_id = ? AND ft.user_id = ? LIMIT 1",
            (rs) -> rs.next() ? new FulfillmentInfo(rs.getString("task_no"), rs.getInt("status"),
                (Long) rs.getObject("assignee_id"),
                rs.getTimestamp("claimed_at") != null ? rs.getTimestamp("claimed_at").toLocalDateTime() : null,
                rs.getTimestamp("shipped_at") != null ? rs.getTimestamp("shipped_at").toLocalDateTime() : null,
                rs.getString("carrier"), rs.getString("tracking_no")) : null,
            orderId, userId
        );

        var timeline = jdbc.query(
            "SELECT from_status, to_status, operator, reason, created_at " +
            "FROM order_status_logs WHERE order_no = ? ORDER BY created_at",
            (rs, rn) -> new StatusLogEntry(
                (Integer) rs.getObject("from_status"), rs.getInt("to_status"),
                rs.getString("operator"), rs.getString("reason"),
                rs.getTimestamp("created_at").toLocalDateTime()),
            orderNo
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
