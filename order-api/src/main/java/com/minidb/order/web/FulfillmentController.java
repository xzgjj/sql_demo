package com.minidb.order.web;

import com.minidb.order.dto.*;
import com.minidb.order.service.FulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fulfillment")
@Tag(name = "Fulfillment", description = "履约管理")
public class FulfillmentController {
    private final FulfillmentService fulfillmentService;

    public FulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping("/tasks")
    @Operation(summary = "查询任务列表", description = "按状态筛选履约任务，支持分页。")
    public ResponseEntity<ApiResponse<FulfillmentService.TaskListPage>> listTasks(
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.listTasks(status, page, pageSize)));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务详情")
    public ResponseEntity<ApiResponse<FulfillmentService.TaskInfo>> getTask(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.getTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/claim")
    @Operation(summary = "领取任务", description = "使用乐观锁防并发。需提供当前version。")
    public ResponseEntity<ApiResponse<Void>> claimTask(
            @PathVariable("taskId") Long taskId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(name = "version") int version) {
        fulfillmentService.claimTask(taskId, userId, version);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/tasks/{taskId}/pick")
    @Operation(summary = "拣货完成", description = "将任务从PICKING变为PICKED。只有当前认领人可操作。必须携带 Idempotency-Key。")
    public ResponseEntity<ApiResponse<Void>> pickTask(
            @PathVariable("taskId") Long taskId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        fulfillmentService.pickTask(taskId, userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/tasks/{taskId}/ship")
    @Operation(summary = "发货", description = "事务内更新任务、订单、发货记录、库存。")
    public ResponseEntity<ApiResponse<Void>> shipOrder(
            @PathVariable("taskId") Long taskId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ShipOrderRequest request) {
        fulfillmentService.shipOrder(taskId, request.carrier(), request.trackingNo(), userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
