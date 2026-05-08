package com.minidb.order.service;

import com.minidb.mvcc.IsolationLevel;
import com.minidb.mvcc.ScenarioRunner;
import com.minidb.mvcc.ScenarioStep;
import com.minidb.mvcc.TransactionManager;
import com.minidb.mvcc.VersionedKVStore;
import com.minidb.order.BusinessException;
import com.minidb.order.OrderStatus;
import com.minidb.order.dto.CreateOrderRequest;
import com.minidb.order.dto.PaymentCallbackRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class ConsoleService {
    private static final int DEMO_ORDER_TARGET = 5;
    private final JdbcTemplate jdbc;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final OutboxProcessor outboxProcessor;
    private final FulfillmentService fulfillmentService;
    private final String mockSignSecret;
    private final int shardCount;
    private final boolean demoEnabled;

    public ConsoleService(JdbcTemplate jdbc, OrderService orderService, PaymentService paymentService,
                          OutboxProcessor outboxProcessor, FulfillmentService fulfillmentService,
                          @Value("${minidb.payment.mock-sign-secret}") String mockSignSecret,
                          @Value("${minidb.order.shard-count:4}") int shardCount,
                          @Value("${minidb.console.demo-enabled:false}") boolean demoEnabled) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.outboxProcessor = outboxProcessor;
        this.fulfillmentService = fulfillmentService;
        this.mockSignSecret = mockSignSecret;
        this.shardCount = shardCount;
        this.demoEnabled = demoEnabled;
    }

    public DashboardSummary dashboardSummary() {
        long ordersToday = count("SELECT COUNT(*) FROM orders WHERE created_at >= CURRENT_DATE");
        long paidSuccess = count("SELECT COUNT(*) FROM payments WHERE status = 20");
        long fulfillmentTotal = count("SELECT COUNT(*) FROM fulfillment_tasks");
        long openExceptions = count("SELECT COUNT(*) FROM exception_tickets WHERE status IN (10,20)");
        long outboxBacklog = count("SELECT COUNT(*) FROM outbox_events WHERE status IN (10,20,40)");
        long routeMiss = count("SELECT COUNT(*) FROM exception_tickets WHERE reason_code = 'ROUTE_NOT_FOUND'");
        var statusDistribution = jdbc.query(
                "SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status ORDER BY status",
                (rs, rn) -> new StatusCount(rs.getInt("status"), rs.getLong("cnt")));
        var recentExceptions = jdbc.query(
                "SELECT id, biz_type, biz_no, reason_code, status, created_at FROM exception_tickets " +
                        "ORDER BY created_at DESC LIMIT 5",
                (rs, rn) -> new ExceptionBrief(rs.getLong("id"), rs.getString("biz_type"),
                        rs.getString("biz_no"), rs.getString("reason_code"), rs.getInt("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
        var workQueue = jdbc.query(
                "SELECT ft.id, ft.task_no, o.order_no, ft.status, ft.version, ft.created_at " +
                        "FROM fulfillment_tasks ft JOIN orders o ON ft.order_id = o.id " +
                        "WHERE ft.status IN (10,20,30,90) ORDER BY ft.created_at DESC LIMIT 6",
                (rs, rn) -> new WorkItem(rs.getLong("id"), rs.getString("task_no"),
                        rs.getString("order_no"), rs.getInt("status"), rs.getInt("version"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
        return new DashboardSummary(ordersToday, paidSuccess, fulfillmentTotal, openExceptions,
                outboxBacklog, routeMiss, statusDistribution, recentExceptions, workQueue);
    }

    public DemoLoadResult loadDemoData() {
        if (!demoEnabled) {
            throw new BusinessException("DEMO_LOAD_DISABLED", "Demo loading is disabled");
        }
        var existing = demoOrderNos();
        if (existing.size() >= DEMO_ORDER_TARGET) {
            return new DemoLoadResult(existing, count("SELECT COUNT(*) FROM fulfillment_tasks"),
                    count("SELECT COUNT(*) FROM exception_tickets"));
        }
        createOrder(501L, "console-pending-501");
        createPaidOrder(501L, "console-paid-501", false);
        var exception = createPaidOrder(501L, "console-exception-501", true);
        markOrderException(exception, "Payment amount mismatch");
        createCancelledOrder(501L, "console-cancelled-501");
        var shipped = createPaidOrder(501L, "console-shipped-501", false);
        outboxProcessor.processOutbox();
        var tasks = jdbc.query("SELECT id, version FROM fulfillment_tasks ORDER BY id LIMIT 3",
                (rs, rn) -> new long[]{rs.getLong("id"), rs.getInt("version")});
        if (!tasks.isEmpty()) {
            fulfillmentService.claimTask(tasks.get(0)[0], 2001L, (int) tasks.get(0)[1]);
        }
        if (tasks.size() > 1) {
            long taskId = tasks.get(1)[0];
            fulfillmentService.claimTask(taskId, 2002L, (int) tasks.get(1)[1]);
            fulfillmentService.pickTask(taskId, 2002L);
        }
        shipIfTaskExists(shipped, 2003L);
        ensureRoutingException();
        return new DemoLoadResult(demoOrderNos(),
                count("SELECT COUNT(*) FROM fulfillment_tasks"),
                count("SELECT COUNT(*) FROM exception_tickets"));
    }

    public OrderTrace traceOrder(long orderId) {
        var order = jdbc.query(
                "SELECT id, order_no, user_id, status, total_amount, paid_amount, created_at FROM orders WHERE id = ?",
                rs -> {
                    if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
                    return new TraceOrder(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                            rs.getInt("status"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
                            rs.getTimestamp("created_at").toLocalDateTime());
                },
                orderId);
        int shard = (int) Math.floorMod(order.userId(), shardCount);
        var route = jdbc.query("SELECT order_no, payment_no, user_id, biz_type FROM order_route WHERE order_no = ?",
                (rs, rn) -> new RouteEntry(rs.getString("order_no"), rs.getString("payment_no"),
                        rs.getLong("user_id"), rs.getString("biz_type")),
                order.orderNo());
        var idempotency = jdbc.query(
                "SELECT idempotency_key, actor_type, actor_id, resource_type, resource_id, status, created_at " +
                        "FROM idempotency_records ORDER BY created_at DESC LIMIT 10",
                (rs, rn) -> new IdempotencyTrace(rs.getString("idempotency_key"), rs.getString("actor_type"),
                        rs.getLong("actor_id"), rs.getString("resource_type"), (Long) rs.getObject("resource_id"),
                        rs.getInt("status"), rs.getTimestamp("created_at").toLocalDateTime()));
        var outbox = jdbc.query(
                "SELECT id, event_type, aggregate_type, aggregate_id, status, retry_count, created_at " +
                        "FROM outbox_events WHERE aggregate_id = ? ORDER BY id",
                (rs, rn) -> new OutboxTrace(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getLong("aggregate_id"), rs.getInt("status"),
                        rs.getInt("retry_count"), rs.getTimestamp("created_at").toLocalDateTime()),
                orderId);
        var timeline = jdbc.query(
                "SELECT from_status, to_status, operator, reason, created_at FROM order_status_logs " +
                        "WHERE order_id = ? ORDER BY created_at",
                (rs, rn) -> new TimelineTrace((Integer) rs.getObject("from_status"), rs.getInt("to_status"),
                        rs.getString("operator"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                orderId);
        var sql = List.of(
                "SELECT * FROM orders WHERE id = ?",
                "SELECT * FROM order_route WHERE order_no = ?",
                "SELECT * FROM outbox_events WHERE aggregate_id = ?"
        );
        return new OrderTrace(order, "user_id " + order.userId() + " -> " + order.userId() + " % " + shardCount + " -> shard_" + shard,
                "auto-commit statements; no cross-shard transaction", route, idempotency, outbox, timeline, sql);
    }

    public RoutePreview previewRoute(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (normalized.length() > 1000) {
            throw new BusinessException("SQL_TOO_LONG", "SQL preview is limited to 1000 chars");
        }
        String lower = normalized.toLowerCase();
        String keyType = "UNKNOWN";
        String target = "REJECT";
        String reason = "缺少 user_id/order_no/payment_no，真实代理会拒绝高风险查询";
        Long userId = findLongAfter(lower, "user_id");
        if (userId != null) {
            keyType = "USER_ID";
            target = "shard_" + Math.floorMod(userId, shardCount);
            reason = "user_id 直接分片路由";
        } else if (lower.contains("order_no")) {
            keyType = "ORDER_NO";
            target = "RouteTableLookup -> shard";
            reason = "通过 PRIMARY order_route 查询 user_id 后路由";
        } else if (lower.contains("payment_no")) {
            keyType = "PAYMENT_NO";
            target = "RouteTableLookup -> shard";
            reason = "通过 PRIMARY order_route 查询 user_id 后路由";
        } else if (lower.contains("products") || lower.contains("product_inventory") ||
                lower.contains("idempotency_records") || lower.contains("exception_tickets") ||
                lower.contains("outbox_events") || lower.contains("order_route")) {
            keyType = "PRIMARY_ONLY";
            target = "PRIMARY";
            reason = "控制表强制路由到 PRIMARY";
        }
        return new RoutePreview(normalized, keyType, target, reason, false);
    }

    public LabRunResult runLabScenario(String scenario) {
        String selected = scenario == null ? "create-order" : scenario;
        if ("mvcc-rc-rr".equals(selected)) {
            return runMvccScenario();
        }
        List<String> steps = "payment-callback".equals(selected)
                ? List.of("验证支付签名", "按 payment_no 查询路由", "读取订单快照", "比对支付金额", "更新支付订单或创建异常", "写入事件箱")
                : List.of("开始业务动作", "获取幂等键", "读取商品快照", "条件扣减并锁定库存", "写入订单和明细", "写入事件箱和路由表", "完成幂等记录");
        return new LabRunResult(selected, steps, List.of(previewRoute("SELECT * FROM orders WHERE user_id = 501")),
                "auto-commit split; no cross-shard transaction",
                List.of("Idempotency-Key 返回第一次成功结果"),
                List.of("ORDER_CREATED/ORDER_PAID 由 outbox_events 记录并重试"),
                Map.of());
    }

    private LabRunResult runMvccScenario() {
        var txnManager = new TransactionManager();
        var store = new VersionedKVStore(txnManager);
        var runner = new ScenarioRunner(txnManager, store);
        var result = runner.run(List.of(
                ScenarioStep.begin("t1", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.put("t1", "order:501", "PENDING_PAYMENT".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.commit("t1"),
                ScenarioStep.begin("t2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.get("t2", "order:501", "RC first read"),
                ScenarioStep.begin("t3", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.get("t3", "order:501", "RR first read"),
                ScenarioStep.put("t2", "order:501", "PAID".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.commit("t2"),
                ScenarioStep.get("t3", "order:501", "RR second read")
        ));
        var steps = result.trace().stream()
                .map(e -> e.sequence() + ". " + e.operation() + " " + (e.detail() == null ? "" : e.detail()))
                .toList();
        var chains = Map.of("order:501", store.versionChainPrettyPrint("order:501"));
        return new LabRunResult("mvcc-rc-rr", steps, List.of(),
                "RR 事务保持 Read View；RC 每次读取刷新可见性",
                List.of(), List.of(), chains);
    }

    private CreatedOrder createOrder(long userId, String key) {
        var req = new CreateOrderRequest(userId,
                List.of(new CreateOrderRequest.OrderItemRequest(1001L, 1)), "console demo");
        var response = orderService.createOrder(req, key);
        return new CreatedOrder(response.orderId(), response.orderNo(), userId);
    }

    private CreatedOrder createPaidOrder(long userId, String key, boolean wrongAmount) {
        var order = createOrder(userId, key);
        String paymentNo = paymentService.createPayment(order.orderId(), userId, "mock_pay");
        BigDecimal amount = jdbc.query("SELECT amount FROM payments WHERE payment_no = ?",
                rs -> {
                    if (!rs.next()) throw new BusinessException("PAYMENT_NOT_FOUND", "Payment not found");
                    return rs.getBigDecimal("amount");
                }, paymentNo);
        BigDecimal callbackAmount = wrongAmount ? amount.subtract(BigDecimal.ONE) : amount;
        LocalDateTime paidAt = LocalDateTime.now();
        String status = "SUCCESS";
        paymentService.handleCallback(new PaymentCallbackRequest(paymentNo, "mock_trade_" + paymentNo,
                callbackAmount, paidAt, status,
                sign(paymentNo, callbackAmount, status, paidAt)));
        return order;
    }

    private CreatedOrder createCancelledOrder(long userId, String key) {
        var order = createOrder(userId, key);
        orderService.cancelOrder(order.orderId(), "console demo cancellation",
                key + "-cancel", userId);
        return order;
    }

    private void markOrderException(CreatedOrder order, String reason) {
        int affected = jdbc.update("UPDATE orders SET status = ?, version = version + 1 WHERE id = ? AND status = ?",
                OrderStatus.EXCEPTION.getCode(), order.orderId(), OrderStatus.PENDING_PAYMENT.getCode());
        if (affected > 0) {
            jdbc.update("INSERT INTO order_status_logs (order_id, order_no, from_status, to_status, operator, reason) " +
                            "VALUES (?, ?, ?, ?, 'SYSTEM', ?)",
                    order.orderId(), order.orderNo(), OrderStatus.PENDING_PAYMENT.getCode(),
                    OrderStatus.EXCEPTION.getCode(), reason);
        }
    }

    private void shipIfTaskExists(CreatedOrder order, long operatorId) {
        var task = jdbc.query(
                "SELECT id, version, status FROM fulfillment_tasks WHERE order_id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    return new long[]{rs.getLong("id"), rs.getInt("version"), rs.getInt("status")};
                },
                order.orderId());
        if (task == null) return;
        if (task[2] == 10) {
            fulfillmentService.claimTask(task[0], operatorId, (int) task[1]);
        }
        fulfillmentService.shipOrder(task[0], "demo_express", "DEMO" + order.orderNo(), operatorId);
    }

    private List<String> demoOrderNos() {
        return jdbc.query("SELECT order_no FROM orders WHERE remark = 'console demo' ORDER BY id",
                (rs, rn) -> rs.getString("order_no"));
    }

    private void ensureRoutingException() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exception_tickets WHERE reason_code = 'ROUTE_NOT_FOUND'",
                Integer.class);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO exception_tickets (biz_type,biz_no,reason_code,detail,status) VALUES (?,?,?,?,10)",
                "ROUTING", "ORD-ROUTE-MISSING", "ROUTE_NOT_FOUND", "order_route missing");
    }

    private String sign(String paymentNo, BigDecimal amount, String status, LocalDateTime paidAt) {
        try {
            String content = paymentNo + "|" + amount + "|" + status + "|" + paidAt;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mockSignSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException("PAYMENT_SIGNATURE_ERROR", "Failed to sign mock payment");
        }
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private Long findLongAfter(String lowerSql, String key) {
        int idx = lowerSql.indexOf(key);
        if (idx < 0) return null;
        int eq = lowerSql.indexOf('=', idx);
        if (eq < 0) return null;
        StringBuilder digits = new StringBuilder();
        for (int i = eq + 1; i < lowerSql.length(); i++) {
            char c = lowerSql.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
            else if (!Character.isWhitespace(c)) break;
        }
        return digits.isEmpty() ? null : Long.parseLong(digits.toString());
    }

    private record CreatedOrder(long orderId, String orderNo, long userId) {}

    public record DashboardSummary(long ordersToday, long paidSuccess, long fulfillmentTotal,
                                   long openExceptions, long outboxBacklog, long routeMiss,
                                   List<StatusCount> statusDistribution,
                                   List<ExceptionBrief> recentExceptions,
                                   List<WorkItem> workQueue) {}
    public record StatusCount(int status, long count) {}
    public record ExceptionBrief(long id, String bizType, String bizNo, String reasonCode,
                                 int status, LocalDateTime createdAt) {}
    public record WorkItem(long taskId, String taskNo, String orderNo, int status, int version,
                           LocalDateTime createdAt) {}
    public record DemoLoadResult(List<String> orderNos, long fulfillmentTasks, long exceptions) {}
    public record TraceOrder(long orderId, String orderNo, long userId, int status,
                             BigDecimal totalAmount, BigDecimal paidAmount, LocalDateTime createdAt) {}
    public record RouteEntry(String orderNo, String paymentNo, long userId, String bizType) {}
    public record IdempotencyTrace(String key, String actorType, long actorId, String resourceType,
                                   Long resourceId, int status, LocalDateTime createdAt) {}
    public record OutboxTrace(long id, String eventType, String aggregateType, long aggregateId,
                              int status, int retryCount, LocalDateTime createdAt) {}
    public record TimelineTrace(Integer fromStatus, int toStatus, String operator, String reason,
                                LocalDateTime createdAt) {}
    public record OrderTrace(TraceOrder order, String route, String transactionContext,
                             List<RouteEntry> routeTable, List<IdempotencyTrace> idempotency,
                             List<OutboxTrace> outbox, List<TimelineTrace> timeline,
                             List<String> sqlHistory) {}
    public record RoutePreview(String sql, String keyType, String target, String reason, boolean executed) {}
    public record LabRunResult(String scenario, List<String> steps, List<RoutePreview> routeTrace,
                               String transactionContext, List<String> idempotency,
                               List<String> outbox, Map<String, String> mvccChains) {}
}
