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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ConsoleService {
    private static final int DEMO_ORDER_TARGET = 5;
    private final JdbcTemplate jdbc;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final OutboxProcessor outboxProcessor;
    private final FulfillmentService fulfillmentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final String mockSignSecret;
    private final int shardCount;
    private final boolean demoEnabled;
    private final boolean proxyMode;
    private final String activeProfiles;
    private final String proxyMgmtBaseUrl;

    public ConsoleService(JdbcTemplate jdbc, OrderService orderService, PaymentService paymentService,
                          OutboxProcessor outboxProcessor, FulfillmentService fulfillmentService,
                          IdempotencyService idempotencyService, ObjectMapper objectMapper,
                          Environment environment,
                          @Value("${minidb.payment.mock-sign-secret}") String mockSignSecret,
                          @Value("${minidb.order.shard-count:4}") int shardCount,
                          @Value("${minidb.console.demo-enabled:false}") boolean demoEnabled,
                          @Value("${minidb.order.proxy-mode:false}") boolean proxyMode,
                          @Value("${spring.profiles.active:}") String activeProfiles,
                          @Value("${minidb.proxy.mgmt-url:http://127.0.0.1:4307}") String proxyMgmtBaseUrl) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.outboxProcessor = outboxProcessor;
        this.fulfillmentService = fulfillmentService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.mockSignSecret = mockSignSecret;
        this.shardCount = shardCount;
        this.demoEnabled = demoEnabled;
        this.proxyMode = proxyMode;
        String profiles = activeProfiles == null ? "" : activeProfiles;
        if (profiles.isBlank()) {
            profiles = String.join(",", environment.getActiveProfiles());
        }
        this.activeProfiles = profiles;
        this.proxyMgmtBaseUrl = proxyMgmtBaseUrl;
    }

    public RuntimeMode runtimeMode() {
        String mode = proxyMode ? "proxy" : "single-db";
        boolean testProfile = activeProfiles.contains("test");
        List<String> warnings = proxyMode
                ? List.of("Console-wide aggregation and order_id-only trace are disabled in proxy mode.",
                "Use user_id, order_no or payment_no for sharded business data.")
                : List.of();
        return new RuntimeMode(mode, proxyMode, demoEnabled, testProfile, shardCount, activeProfiles, warnings);
    }

    public DashboardSummary dashboardSummary() {
        ensureSingleDatabaseConsoleQuery("dashboard summary");
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
        ensureSingleDatabaseConsoleQuery("demo data loading");
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

    public DemoLoadResult loadDemoData(String idempotencyKey) {
        ensureSingleDatabaseConsoleQuery("demo data loading");
        String requestBody = toJson(Map.of("action", "console-demo-load"));
        String cached = idempotencyService.tryAcquire(idempotencyKey, "SYSTEM", 0L, requestBody);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, DemoLoadResult.class);
            } catch (Exception e) {
                throw new BusinessException("DESERIALIZATION_ERROR", "Failed to deserialize demo load response");
            }
        }
        try {
            DemoLoadResult result = loadDemoData();
            idempotencyService.markCompleted(idempotencyKey, "SYSTEM", 0L, toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotencyService.markFailed(idempotencyKey, "SYSTEM", 0L);
            throw e;
        }
    }

    public OrderTrace traceOrder(long orderId) {
        ensureSingleDatabaseConsoleQuery("order trace by order_id");
        var order = Objects.requireNonNull(jdbc.query(
                "SELECT id, order_no, user_id, status, total_amount, paid_amount, created_at FROM orders WHERE id = ?",
                rs -> {
                    if (!rs.next()) throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
                    return new TraceOrder(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                            rs.getInt("status"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
                            rs.getTimestamp("created_at").toLocalDateTime());
                },
                orderId));
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

        // Query sql_audit_logs for this order
        List<SqlAuditEntry> auditLogs;
        try {
            auditLogs = jdbc.query(
                "SELECT trace_id, sql_digest, sql_summary, target_ds, target_shard, status, " +
                "error_code, elapsed_ms, created_at FROM sql_audit_logs " +
                "WHERE order_id = ? OR sql_summary LIKE ? ORDER BY created_at DESC LIMIT 20",
                (rs, rn) -> new SqlAuditEntry(
                    rs.getString("trace_id"), rs.getString("sql_digest"),
                    rs.getString("sql_summary"), rs.getString("target_ds"),
                    (Integer) rs.getObject("target_shard"),
                    rs.getString("status"), rs.getString("error_code"),
                    rs.getInt("elapsed_ms"),
                    rs.getTimestamp("created_at").toLocalDateTime()),
                orderId, "%" + order.orderNo() + "%");
        } catch (Exception e) {
            auditLogs = List.of(); // V004 table may not exist yet
        }

        var sql = List.of(
                "SELECT * FROM orders WHERE id = ?",
                "SELECT * FROM order_route WHERE order_no = ?",
                "SELECT * FROM outbox_events WHERE aggregate_id = ?"
        );
        return new OrderTrace(order, "user_id " + order.userId() + " -> " + order.userId() + " % " + shardCount + " -> shard_" + shard,
                proxyMode ? "2PC coordinated across PRIMARY + shard_" + shard : "single-DB transaction",
                route, idempotency, outbox, timeline, sql, auditLogs);
    }

    public RoutePreview previewRoute(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (normalized.length() > 1000) {
            throw new BusinessException("SQL_TOO_LONG", "SQL preview is limited to 1000 chars");
        }
        String lower = normalized.toLowerCase();
        String keyType = "UNKNOWN";
        String target = "REJECT";
        String reason = "MISSING_SHARD_KEY: 缺少 user_id/order_no/payment_no，真实代理会拒绝高风险查询";
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
        if ("mvcc-write-conflict".equals(selected)) {
            return runMvccWriteConflictScenario();
        }
        if ("mvcc-rollback".equals(selected)) {
            return runMvccRollbackScenario();
        }
        if ("mvcc-delete".equals(selected)) {
            return runMvccDeleteScenario();
        }
        List<String> steps = "payment-callback".equals(selected)
                ? List.of("验证支付签名", "按 payment_no 查询路由", "读取订单快照", "比对支付金额", "更新支付订单或创建异常", "写入事件箱")
                : List.of("开始业务动作", "获取幂等键", "读取商品快照", "条件扣减并锁定库存", "写入订单和明细", "写入事件箱和路由表", "完成幂等记录");
        return new LabRunResult(selected, steps, List.of(previewRoute("SELECT * FROM orders WHERE user_id = 501")),
                "auto-commit split; no cross-shard transaction",
                List.of("Idempotency-Key 返回第一次成功结果"),
                List.of("ORDER_CREATED/ORDER_PAID 由 outbox_events 记录并重试"),
                Map.of(), List.of(), List.of("业务场景使用 InnoDB 当前读/条件更新，自研 MVCC 只解释可见性"),
                List.of("写接口必须带 Idempotency-Key", "状态更新必须带原状态条件"), List.of());
    }

    public LabRunResult runCustomScenario(String body) {
        if (body == null || body.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "Custom scenario body is required");
        }
        if (body.length() > 10000) {
            throw new BusinessException("SCENARIO_TOO_LONG", "Custom scenario too long (max 10000 chars)");
        }
        com.minidb.order.dto.CustomScenarioRequest req;
        try {
            req = objectMapper.readValue(body, com.minidb.order.dto.CustomScenarioRequest.class);
        } catch (Exception e) {
            throw new BusinessException("SCENARIO_PARSE_ERROR", "Failed to parse scenario: " + e.getMessage());
        }
        if (!req.isValid()) {
            throw new BusinessException("INVALID_SCENARIO",
                    "Invalid scenario: max 4 transactions, max 20 steps, key max 64 chars, value max 128 chars");
        }

        var txnManager = new TransactionManager();
        var store = new VersionedKVStore(txnManager);
        var runner = new ScenarioRunner(txnManager, store);

        // Build scenario steps from request
        var txnAliasToRef = new java.util.HashMap<String, String>();
        var steps = new ArrayList<ScenarioStep>();
        for (var txnDef : req.transactions()) {
            String ref = txnDef.alias();
            txnAliasToRef.put(ref, ref);
            IsolationLevel level = "REPEATABLE_READ".equals(txnDef.isolationLevel())
                    ? IsolationLevel.REPEATABLE_READ : IsolationLevel.READ_COMMITTED;
            steps.add(ScenarioStep.begin(ref, level));
        }
        for (var stepDef : req.steps()) {
            String ref = stepDef.txnAlias();
            switch (stepDef.action().toUpperCase()) {
                case "PUT" -> steps.add(ScenarioStep.put(ref, stepDef.key(),
                        stepDef.value() != null ? stepDef.value().getBytes(StandardCharsets.UTF_8) : new byte[0]));
                case "GET" -> steps.add(ScenarioStep.get(ref, stepDef.key(), ref + " reads " + stepDef.key()));
                case "DELETE" -> steps.add(ScenarioStep.delete(ref, stepDef.key()));
                case "COMMIT" -> steps.add(ScenarioStep.commit(ref));
                case "ROLLBACK" -> steps.add(ScenarioStep.rollback(ref));
                default -> throw new BusinessException("INVALID_ACTION",
                        "Unknown action: " + stepDef.action() + ". Use PUT/GET/DELETE/COMMIT/ROLLBACK");
            }
        }

        var result = runner.run(steps);
        var mvccSteps = result.trace().stream().map(this::toMvccStepTrace).toList();
        Map<String, String> chains = new java.util.LinkedHashMap<>();
        for (var entry : result.versionChains().entrySet()) {
            chains.put(entry.getKey(), store.versionChainPrettyPrint(entry.getKey()));
        }

        var assertionResults = new ArrayList<String>();
        if (req.assertions() != null) {
            for (String assertion : req.assertions()) {
                assertionResults.add("Assertion: " + assertion + (result.hasErrors()
                        ? " — NEEDS VERIFICATION (scenario had errors)"
                        : " — PASS (manual verification suggested)"));
            }
        }

        return new LabRunResult("custom", result.trace().stream().map(e ->
                String.format("[%d] T%d %s key=%s %s",
                        e.sequence(), e.txnId(), e.operation(), e.key(),
                        e.detail() != null ? e.detail() : "")).toList(),
                List.of(), "Custom scenario — " + req.transactions().size() + " txns, " + req.steps().size() + " steps",
                List.of(), List.of(), chains, mvccSteps,
                req.assertions() != null ? req.assertions() : List.of(),
                assertionResults, result.errors());
    }

    private LabRunResult runMvccRollbackScenario() {
        var txnManager = new TransactionManager();
        var store = new VersionedKVStore(txnManager);
        var runner = new ScenarioRunner(txnManager, store);
        var result = runner.run(List.of(
                ScenarioStep.begin("t1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t1", "inventory:sku-A", "100".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.commit("t1"),
                ScenarioStep.begin("t2", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.get("t2", "inventory:sku-A", "T2 initial read"),
                ScenarioStep.begin("t3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t3", "inventory:sku-A", "80".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.get("t3", "inventory:sku-A", "T3 sees own write"),
                ScenarioStep.rollback("t3"),
                ScenarioStep.get("t2", "inventory:sku-A", "T2 read after T3 rollback"),
                ScenarioStep.commit("t2")
        ));
        var steps = result.trace().stream().map(this::formatMvccStep).toList();
        var mvccSteps = result.trace().stream().map(this::toMvccStepTrace).toList();
        Map<String, String> chains = new java.util.LinkedHashMap<>();
        for (var entry : result.versionChains().entrySet()) {
            chains.put(entry.getKey(), store.versionChainPrettyPrint(entry.getKey()));
        }
        return new LabRunResult("mvcc-rollback", steps, List.of(),
                "ROLLBACK 会撤销未提交版本的写入，通过 Undo Log 恢复上一个版本。",
                List.of("幂等写使用 INSERT ON DUPLICATE KEY UPDATE，回滚不影响已提交数据"),
                List.of(),
                chains, mvccSteps,
                List.of("T2 在 T3 ROLLBACK 后读到的是 T1 提交的值 100，说明回滚正确恢复了版本链。",
                        "T3 的 PUT 创建了一个新版本，但 ROLLBACK 将其撤销并通过 Undo Log 恢复。"),
                List.of("PUT 会覆盖最新版本前先将其保存为 undo 版本。",
                        "ROLLBACK 沿 Undo Log 反向恢复，确保版本链回到事务开始前的状态。"),
                result.errors());
    }

    private LabRunResult runMvccDeleteScenario() {
        var txnManager = new TransactionManager();
        var store = new VersionedKVStore(txnManager);
        var runner = new ScenarioRunner(txnManager, store);
        var result = runner.run(List.of(
                ScenarioStep.begin("t1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t1", "user:501", "active".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.commit("t1"),
                ScenarioStep.begin("t2", IsolationLevel.REPEATABLE_READ),
                ScenarioStep.get("t2", "user:501", "T2 sees user"),
                ScenarioStep.begin("t3", IsolationLevel.READ_COMMITTED),
                ScenarioStep.delete("t3", "user:501"),
                ScenarioStep.get("t3", "user:501", "T3 after delete"),
                ScenarioStep.commit("t3"),
                ScenarioStep.get("t2", "user:501", "T2 after T3 committed delete"),
                ScenarioStep.commit("t2")
        ));
        var steps = result.trace().stream().map(this::formatMvccStep).toList();
        var mvccSteps = result.trace().stream().map(this::toMvccStepTrace).toList();
        Map<String, String> chains = new java.util.LinkedHashMap<>();
        for (var entry : result.versionChains().entrySet()) {
            chains.put(entry.getKey(), store.versionChainPrettyPrint(entry.getKey()));
        }
        return new LabRunResult("mvcc-delete", steps, List.of(),
                "RC 事务 T3 提交 DELETE 后，RR 事务 T2 的 Read View 仍能看到被删除的版本。",
                List.of(), List.of(),
                chains, mvccSteps,
                List.of("T2 使用 RR：T3 提交 DELETE 后 T2 仍能看到 'active'，因为 Read View 在 BEGIN 时已固定。",
                        "T3 自身在 DELETE 后 GET 返回 NOT_FOUND，因为 DELETE 对自身可见。"),
                List.of("DELETE 不删除数据，而是标记 deletedTxnId。",
                        "可见性判断同时检查 createdTxnId 和 deletedTxnId。",
                        "RR Read View 在事务开始时建立，后续提交的 DELETE 对其不可见。"),
                result.errors());
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
                ScenarioStep.begin("t4", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t4", "order:501", "PAID".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.commit("t4"),
                ScenarioStep.get("t2", "order:501", "RC second read"),
                ScenarioStep.get("t3", "order:501", "RR second read")
        ));
        var steps = result.trace().stream()
                .map(this::formatMvccStep)
                .toList();
        var mvccSteps = result.trace().stream()
                .map(this::toMvccStepTrace)
                .toList();
        var chains = Map.of("order:501", store.versionChainPrettyPrint("order:501"));
        return new LabRunResult("mvcc-rc-rr", steps, List.of(),
                "RR 事务保持 Read View；RC 每次读取刷新可见性",
                List.of(), List.of(), chains, mvccSteps,
                List.of(
                        "T2 使用 READ_COMMITTED：第一次读到 PENDING_PAYMENT，T4 提交 PAID 后第二次读到 PAID。",
                        "T3 使用 REPEATABLE_READ：第一次读建立稳定 Read View，T4 后提交版本仍不可见。"
                ),
                List.of(
                        "RC 第二次读取返回 PAID，说明已提交的新版本对新的 Read View 可见。",
                        "RR 第二次读取仍返回 PENDING_PAYMENT，说明后提交的 PAID 版本对该 Read View 不可见。",
                        "版本链保留最新版本和历史版本，用于解释为什么可见或不可见。"
                ),
                result.errors());
    }

    private LabRunResult runMvccWriteConflictScenario() {
        var txnManager = new TransactionManager();
        var store = new VersionedKVStore(txnManager);
        var runner = new ScenarioRunner(txnManager, store);
        var result = runner.run(List.of(
                ScenarioStep.begin("t1", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t1", "inventory:sku-1001", "locked-by-t1".getBytes(StandardCharsets.UTF_8)),
                ScenarioStep.begin("t2", IsolationLevel.READ_COMMITTED),
                ScenarioStep.put("t2", "inventory:sku-1001", "locked-by-t2".getBytes(StandardCharsets.UTF_8))
        ));
        var steps = result.trace().stream()
                .map(this::formatMvccStep)
                .toList();
        var mvccSteps = result.trace().stream()
                .map(this::toMvccStepTrace)
                .toList();
        var chains = Map.of("inventory:sku-1001", store.versionChainPrettyPrint("inventory:sku-1001"));
        return new LabRunResult("mvcc-write-conflict", steps, List.of(),
                "T2 attempts to write a key whose latest version is still owned by active T1.",
                List.of(), List.of("Write conflict protects the latest uncommitted version."), chains,
                mvccSteps,
                List.of("写写冲突不依赖快照读：写入前必须检查 key 的最新版本是否由活跃事务持有。"),
                List.of("T2 写同一 key 被拒绝，避免覆盖 T1 未提交版本。"),
                result.errors());
    }

    private String formatMvccStep(com.minidb.mvcc.TraceEvent event) {
        String detail = event.detail() == null ? "" : " " + event.detail();
        String key = event.key() == null || event.key().isBlank() ? "" : " key=" + event.key();
        return event.sequence() + ". txn=" + event.txnId() + " " + event.operation() + key + detail;
    }

    private MvccStepTrace toMvccStepTrace(com.minidb.mvcc.TraceEvent event) {
        String value = event.valueSnapshot() == null
                ? null
                : new String(event.valueSnapshot(), StandardCharsets.UTF_8);

        ReadViewInfo rv = null;
        if (event.readView() != null) {
            var r = event.readView();
            rv = new ReadViewInfo(r.creatorTxnId(), r.lowWatermark(), r.highWatermark(),
                    List.copyOf(r.activeTxnIds()), event.isolationLevel());
        }

        return new MvccStepTrace(event.sequence(), event.txnId(), event.operation(),
                event.key(), value, event.detail(), explainMvccEvent(event),
                rv, null);
    }

    private String explainMvccEvent(com.minidb.mvcc.TraceEvent event) {
        if ("BEGIN".equals(event.operation())) {
            return event.detail() != null && event.detail().contains("REPEATABLE_READ")
                    ? "开启 RR 事务；第一次一致性读会建立稳定 Read View。"
                    : "开启 RC 事务；每次一致性读都会重新判断已提交版本。";
        }
        if ("GET".equals(event.operation())) {
            return "沿版本链从新到旧检查提交状态和 Read View，可见的第一个版本就是读结果。";
        }
        if ("PUT".equals(event.operation())) {
            return "写入会先检查最新版本，若最新版本属于其他活跃事务则触发写写冲突。";
        }
        if ("COMMIT".equals(event.operation())) {
            return "提交后该事务写入的版本对后续 RC 读可见；已有 RR Read View 不会自动扩大。";
        }
        if ("ROLLBACK".equals(event.operation())) {
            return "回滚会撤销该事务未提交版本，后续读回到上一条可见版本。";
        }
        if ("ERROR".equals(event.operation())) {
            return "场景在此处触发预期保护逻辑，返回已执行步骤和错误位置。";
        }
        return "记录该步骤对版本链和可见性的影响。";
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
        if (amount == null) {
            throw new BusinessException("PAYMENT_AMOUNT_NOT_FOUND", "Payment amount not found");
        }
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
        int affected = jdbc.update("UPDATE orders SET status = ?, version = version + 1 WHERE id = ? AND user_id = ? AND status = ?",
                OrderStatus.EXCEPTION.getCode(), order.orderId(), order.userId(), OrderStatus.PENDING_PAYMENT.getCode());
        if (affected > 0) {
            jdbc.update("INSERT INTO order_status_logs (order_id, order_no, from_status, to_status, operator, reason) " +
                            "VALUES (?, ?, ?, ?, 'SYSTEM', ?)",
                    order.orderId(), order.orderNo(), OrderStatus.PENDING_PAYMENT.getCode(),
                    OrderStatus.EXCEPTION.getCode(), reason);
        }
    }

    private void shipIfTaskExists(CreatedOrder order, long operatorId) {
        var task = jdbc.query(
                "SELECT id, version, status, assignee_id FROM fulfillment_tasks WHERE order_id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    return new long[]{rs.getLong("id"), rs.getInt("version"), rs.getInt("status"),
                            rs.getObject("assignee_id") != null ? rs.getLong("assignee_id") : 0L};
                },
                order.orderId());
        if (task == null) return;
        long shipOperatorId = task[3] != 0L ? task[3] : operatorId;
        if (task[2] == 10) {
            fulfillmentService.claimTask(task[0], operatorId, (int) task[1]);
            shipOperatorId = operatorId;
        }
        fulfillmentService.shipOrder(task[0], "demo_express", "DEMO" + order.orderNo(), shipOperatorId);
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
                "ROUTING", "ORD-ROUTE-MISSING", "ROUTE_NOT_FOUND", "{\"detail\":\"order_route missing\"}");
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
        Long value = jdbc.queryForObject(Objects.requireNonNull(sql, "sql"), Long.class);
        return value == null ? 0 : value;
    }

    private void ensureSingleDatabaseConsoleQuery(String operation) {
        if (proxyMode) {
            throw new BusinessException("PROXY_MODE_UNSUPPORTED_QUERY",
                    operation + " requires a single-database connection. "
                    + "Use direct mode for console-wide aggregation, or query by user_id/order_no/payment_no.");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize console response");
        }
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
                             List<String> sqlHistory,
                             List<SqlAuditEntry> sqlAuditLogs) {}

    public record SqlAuditEntry(String traceId, String sqlDigest, String sqlSummary,
                                 String targetDs, Integer targetShard, String status,
                                 String errorCode, int elapsedMs,
                                 LocalDateTime createdAt) {}
    public record RoutePreview(String sql, String keyType, String target, String reason, boolean executed) {}
    public record MvccStepTrace(long sequence, long txnId, String operation, String key,
                                String value, String detail, String explanation,
                                ReadViewInfo readView, List<VersionNode> versionChain) {}

    public record ReadViewInfo(long creatorTxnId, long lowWatermark, long highWatermark,
                                List<Long> activeTxnIds, String isolationLevel) {}

    public record VersionNode(String value, long createdByTxnId, long deletedByTxnId,
                               boolean isLatest, String txnStatus) {}
    public record LabRunResult(String scenario, List<String> steps, List<RoutePreview> routeTrace,
                               String transactionContext, List<String> idempotency,
                               List<String> outbox, Map<String, String> mvccChains,
                               List<MvccStepTrace> mvccSteps, List<String> readViews,
                               List<String> assertions, List<String> errors) {}
    public record RuntimeMode(String mode, boolean proxyMode, boolean demoEnabled, boolean testProfile,
                              int shardCount, String activeProfiles, List<String> warnings) {}

    // ---- proxy management / observability ----

    private static final java.util.logging.Logger PROXY_LOG =
            java.util.logging.Logger.getLogger(ConsoleService.class.getName());

    @SuppressWarnings("unchecked")
    public ProxySessionsResult proxySessions() {
        try {
            Map<String, Object> data = fetchJson("/sessions");
            List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.getOrDefault("sessions", List.of());
            return new ProxySessionsResult(sessions, ((Number) data.getOrDefault("count", 0)).intValue());
        } catch (Exception e) {
            PROXY_LOG.fine("Proxy management /sessions unavailable: " + e.getMessage());
            return new ProxySessionsResult(List.of(), 0);
        }
    }

    @SuppressWarnings("unchecked")
    public ProxyPoolsResult proxyPools() {
        try {
            Map<String, Object> data = fetchJson("/pools");
            Map<String, Object> pools = (Map<String, Object>) data.getOrDefault("pools", Map.of());
            return new ProxyPoolsResult(pools, ((Number) data.getOrDefault("totalActive", 0)).intValue());
        } catch (Exception e) {
            PROXY_LOG.fine("Proxy management /pools unavailable: " + e.getMessage());
            return new ProxyPoolsResult(Map.of(), 0);
        }
    }

    @SuppressWarnings("unchecked")
    public ProxyDecisionsResult proxyDecisions(String sessionId, int limit) {
        try {
            StringBuilder path = new StringBuilder("/decisions?limit=").append(Math.min(limit, 200));
            if (sessionId != null && !sessionId.isBlank()) {
                path.append("&sessionId=").append(java.net.URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
            }
            Map<String, Object> data = fetchJson(path.toString());
            List<Map<String, Object>> decisions = (List<Map<String, Object>>) data.getOrDefault("decisions", List.of());
            return new ProxyDecisionsResult(decisions, ((Number) data.getOrDefault("count", 0)).intValue());
        } catch (Exception e) {
            PROXY_LOG.fine("Proxy management /decisions unavailable: " + e.getMessage());
            return new ProxyDecisionsResult(List.of(), 0);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJson(String path) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(proxyMgmtBaseUrl + path);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("Proxy management API returned " + status);
            }
            byte[] bytes = conn.getInputStream().readAllBytes();
            return objectMapper.readValue(bytes, Map.class);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public record ProxySessionsResult(List<Map<String, Object>> sessions, int count) {}
    public record ProxyPoolsResult(Map<String, Object> pools, int totalActive) {}
    public record ProxyDecisionsResult(List<Map<String, Object>> decisions, int count) {}
}
