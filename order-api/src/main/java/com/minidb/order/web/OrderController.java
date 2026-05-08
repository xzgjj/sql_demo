package com.minidb.order.web;

import com.minidb.order.dto.*;
import com.minidb.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "订单管理")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "创建订单", description = "包含幂等检查、库存锁定、订单创建。必须携带 Idempotency-Key。")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @RequestHeader("Idempotency-Key") @Parameter(description = "幂等键", required = true) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @Operation(summary = "查询订单列表", description = "按用户ID查询订单，支持状态筛选和分页。")
    public ResponseEntity<ApiResponse<OrderService.OrderListPage>> listOrders(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return ResponseEntity.ok(ApiResponse.ok(orderService.listOrders(userId, status, page, pageSize)));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "查询订单详情", description = "返回订单、明细、支付、履约和状态时间线。")
    public ResponseEntity<ApiResponse<OrderService.OrderDetail>> getOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getOrder(orderId)));
    }

    @GetMapping("/by-no/{orderNo}")
    @Operation(summary = "按订单号查询", description = "根据订单号精确查找订单详情。")
    public ResponseEntity<ApiResponse<OrderService.OrderDetail>> getOrderByNo(@PathVariable String orderNo) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getOrderByNo(orderNo)));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "取消订单", description = "待支付取消释放库存，已支付取消进入退款中。已发货不可取消。")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CancelOrderRequest request) {
        orderService.cancelOrder(orderId, request.reason(), idempotencyKey, userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
