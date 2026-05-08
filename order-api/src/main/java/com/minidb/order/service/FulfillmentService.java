package com.minidb.order.service;

import com.minidb.order.FulfillmentStatus;
import com.minidb.order.OrderStatus;
import com.minidb.order.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 履约服务。处理任务创建、领取（乐观锁）、拣货、发货。
 * 领取使用 version + status 乐观锁防止并发冲突。
 */
@Service
public class FulfillmentService {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);
    private final JdbcTemplate jdbc;
    private final InventoryService inventoryService;
    private final OrderService orderService;

    public FulfillmentService(JdbcTemplate jdbc, InventoryService inventoryService,
                              OrderService orderService) {
        this.jdbc = jdbc;
        this.inventoryService = inventoryService;
        this.orderService = orderService;
    }

    /**
     * 由 outbox 处理器调用：支付成功后自动创建履约任务。
     */
    @Transactional
    public void createTaskFromOrderPaid(long orderId) {
        var order = jdbc.query(
            "SELECT order_no, user_id FROM orders WHERE id = ? AND status = ?",
            rs -> {
                if (!rs.next()) return null;
                return new Object[]{rs.getString("order_no"), rs.getLong("user_id")};
            },
            orderId, OrderStatus.PAID.getCode()
        );
        if (order == null) {
            log.warn("Order {} not in PAID status, skip task creation", orderId);
            return;
        }

        // 检查是否已存在履约任务
        var existing = jdbc.query(
            "SELECT id FROM fulfillment_tasks WHERE order_id = ?",
            rs -> rs.next() ? rs.getLong("id") : null,
            orderId
        );
        if (existing != null) {
            log.info("Fulfillment task already exists for order {}", orderId);
            return;
        }

        String orderNo = (String) order[0];
        long userId = (long) order[1];
        String taskNo = generateTaskNo();

        jdbc.update(
            "INSERT INTO fulfillment_tasks (user_id, task_no, order_id, warehouse_id, status, version) " +
            "VALUES (?, ?, ?, 1, ?, 0)",
            userId, taskNo, orderId, FulfillmentStatus.PENDING_CLAIM.getCode()
        );

        // 更新订单状态：已支付 → 待履约
        jdbc.update(
            "UPDATE orders SET status = ?, version = version + 1 WHERE id = ? AND status = ?",
            OrderStatus.PENDING_FULFILLMENT.getCode(), orderId, OrderStatus.PAID.getCode()
        );
        jdbc.update(
            "INSERT INTO order_status_logs (order_id, order_no, from_status, to_status, operator, reason) " +
            "VALUES (?, ?, ?, ?, 'SYSTEM', 'Fulfillment task created')",
            orderId, orderNo, OrderStatus.PAID.getCode(), OrderStatus.PENDING_FULFILLMENT.getCode()
        );

        log.info("Fulfillment task created: taskNo={}, orderId={}", taskNo, orderId);
    }

    /**
     * 领取履约任务。
     * 使用乐观锁：WHERE id = ? AND status = ? AND version = ?
     * 同时更新 status → PICKING。
     */
    @Transactional
    public void claimTask(long taskId, long userId, int expectedVersion) {
        int affected = jdbc.update(
            "UPDATE fulfillment_tasks SET status = ?, assignee_id = ?, claimed_at = NOW(), version = version + 1 " +
            "WHERE id = ? AND status = ? AND version = ?",
            FulfillmentStatus.PICKING.getCode(), userId, taskId,
            FulfillmentStatus.PENDING_CLAIM.getCode(), expectedVersion
        );

        if (affected == 0) {
            throw new BusinessException("TASK_ALREADY_CLAIMED",
                "Task " + taskId + " already claimed or version mismatch");
        }

        log.info("Task {} claimed by user {}", taskId, userId);
    }

    /**
     * 查看任务详情（含version用于乐观锁）。
     */
    public TaskInfo getTask(long taskId) {
        return jdbc.query(
            "SELECT id, task_no, user_id, order_id, status, assignee_id, version FROM fulfillment_tasks WHERE id = ?",
            rs -> {
                if (!rs.next()) throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
                return new TaskInfo(
                    rs.getLong("id"), rs.getString("task_no"), rs.getLong("user_id"),
                    rs.getLong("order_id"), rs.getInt("status"),
                    rs.getLong("assignee_id"), rs.getInt("version")
                );
            },
            taskId
        );
    }

    /**
     * 发货。
     * 事务内：更新履约任务 → 更新订单 → 创建发货记录 → 库存 locked→shipped → outbox。
     */
    @Transactional
    public void shipOrder(long taskId, String carrier, String trackingNo, long operatorId) {
        // 1. 查询任务和关联订单
        var task = jdbc.query(
            "SELECT ft.task_no, ft.user_id, ft.order_id, ft.status, ft.version, " +
            "o.order_no, o.status as order_status, o.version as order_version " +
            "FROM fulfillment_tasks ft JOIN orders o ON ft.order_id = o.id " +
            "WHERE ft.id = ?",
            rs -> {
                if (!rs.next()) throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
                return new Object[]{
                    rs.getString("task_no"), rs.getLong("user_id"), rs.getLong("order_id"),
                    rs.getInt("status"), rs.getInt("version"),
                    rs.getString("order_no"), rs.getInt("order_status"), rs.getInt("order_version")
                };
            },
            taskId
        );

        String taskNo = (String) task[0];
        long userId = (long) task[1];
        long orderId = (long) task[2];
        int taskStatus = (int) task[3];
        String orderNo = (String) task[5];

        if (taskStatus == FulfillmentStatus.SHIPPED.getCode()) {
            throw new BusinessException("TASK_ALREADY_SHIPPED", "Task already shipped: " + taskNo);
        }

        // 2. 更新履约任务 → SHIPPED
        jdbc.update(
            "UPDATE fulfillment_tasks SET status = ?, shipped_at = NOW(), version = version + 1 " +
            "WHERE id = ? AND status IN (?, ?)",
            FulfillmentStatus.SHIPPED.getCode(), taskId,
            FulfillmentStatus.PICKING.getCode(), FulfillmentStatus.PICKED.getCode()
        );

        // 3. 更新订单 → SHIPPED
        int orderUpdated = jdbc.update(
            "UPDATE orders SET status = ?, version = version + 1 WHERE id = ? AND status = ?",
            OrderStatus.SHIPPED.getCode(), orderId, OrderStatus.PENDING_FULFILLMENT.getCode()
        );
        if (orderUpdated == 0) {
            throw new BusinessException("ORDER_STATUS_CHANGED", "Order not in PENDING_FULFILLMENT status");
        }

        // 4. 创建发货记录
        jdbc.update(
            "INSERT INTO shipments (user_id, order_id, task_id, carrier, tracking_no) VALUES (?, ?, ?, ?, ?)",
            userId, orderId, taskId, carrier, trackingNo
        );

        // 5. 更新库存 locked → shipped
        var items = jdbc.query(
            "SELECT product_id, quantity FROM order_items WHERE order_id = ?",
            (rs, rowNum) -> new InventoryService.StockLockItem(
                rs.getLong("product_id"), rs.getInt("quantity"), orderNo
            ),
            orderId
        );
        for (var item : items) {
            inventoryService.shipStock(item.productId(), item.quantity());
            inventoryService.writeJournal(item.productId(), "FULFILL_SHIP", orderNo, 0, -item.quantity(), item.quantity());
        }

        // 6. 状态日志
        jdbc.update(
            "INSERT INTO order_status_logs (order_id, order_no, from_status, to_status, operator, reason) " +
            "VALUES (?, ?, ?, ?, 'USER', ?)",
            orderId, orderNo, OrderStatus.PENDING_FULFILLMENT.getCode(), OrderStatus.SHIPPED.getCode(),
            "Shipped via " + carrier
        );

        // 7. outbox
        String payload;
        try {
            java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
            p.put("order_no", orderNo);
            p.put("tracking_no", trackingNo);
            payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);
        } catch (Exception e) {
            throw new BusinessException("SERIALIZATION_ERROR", "Failed to serialize payload");
        }
        orderService.writeOutbox("ORDER_SHIPPED", "ORDER", orderId, payload);

        log.info("Order shipped: orderNo={}, trackingNo={}", orderNo, trackingNo);
    }

    private String generateTaskNo() {
        return "FUL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
               String.format("%04d", (int) (Math.random() * 10000));
    }

    // ---- Query methods ----

    public record TaskListPage(java.util.List<TaskSummary> items, int page, int pageSize, long total) {}

    public record TaskSummary(long taskId, String taskNo, long userId, long orderId,
                              String orderNo, int status, Long assigneeId,
                              int version, java.time.LocalDateTime claimedAt,
                              java.time.LocalDateTime createdAt) {}

    public TaskListPage listTasks(Integer status, int page, int pageSize) {
        var conditions = new java.util.ArrayList<String>();
        var params = new java.util.ArrayList<Object>();
        if (status != null) {
            conditions.add("ft.status = ?");
            params.add(status);
        }
        String whereClause = conditions.isEmpty() ? "1=1" : String.join(" AND ", conditions);

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM fulfillment_tasks ft WHERE " + whereClause,
            Long.class, params.toArray()
        );

        int offset = (page - 1) * pageSize;
        var queryParams = new java.util.ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        var items = jdbc.query(
            "SELECT ft.id, ft.task_no, ft.user_id, ft.order_id, o.order_no, " +
            "ft.status, ft.assignee_id, ft.version, ft.claimed_at, ft.created_at " +
            "FROM fulfillment_tasks ft JOIN orders o ON ft.order_id = o.id " +
            "WHERE " + whereClause + " ORDER BY ft.created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> new TaskSummary(
                rs.getLong("id"), rs.getString("task_no"), rs.getLong("user_id"),
                rs.getLong("order_id"), rs.getString("order_no"), rs.getInt("status"),
                (Long) rs.getObject("assignee_id"),
                rs.getInt("version"),
                rs.getTimestamp("claimed_at") != null ? rs.getTimestamp("claimed_at").toLocalDateTime() : null,
                rs.getTimestamp("created_at").toLocalDateTime()
            ),
            queryParams.toArray()
        );

        return new TaskListPage(items, page, pageSize, total != null ? total : 0);
    }

    @Transactional
    public void pickTask(long taskId, long userId) {
        int affected = jdbc.update(
            "UPDATE fulfillment_tasks SET status = ?, version = version + 1 " +
            "WHERE id = ? AND status = ? AND assignee_id = ?",
            FulfillmentStatus.PICKED.getCode(), taskId,
            FulfillmentStatus.PICKING.getCode(), userId
        );
        if (affected == 0) {
            var current = jdbc.query(
                "SELECT status, assignee_id FROM fulfillment_tasks WHERE id = ?",
                rs -> rs.next() ? new Object[]{rs.getInt("status"), (Long) rs.getObject("assignee_id")} : null,
                taskId
            );
            if (current == null) throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + taskId);
            if ((int) current[0] != FulfillmentStatus.PICKING.getCode())
                throw new BusinessException("TASK_STATUS_ERROR", "Task must be in PICKING status, current: " + current[0]);
            throw new BusinessException("TASK_NOT_ASSIGNED", "Task not assigned to user " + userId);
        }
        log.info("Task {} picked by user {}", taskId, userId);
    }

    public record TaskInfo(long id, String taskNo, long userId, long orderId, int status,
                           Long assigneeId, int version) {}
}
