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
