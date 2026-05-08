package com.minidb.order.web;

import com.minidb.order.dto.*;
import com.minidb.order.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Payments", description = "支付管理")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{orderId}/payments")
    @Operation(summary = "创建支付单")
    public ResponseEntity<ApiResponse<Map<String, String>>> createPayment(
            @PathVariable Long orderId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "mock_pay") String channel) {
        String paymentNo = paymentService.createPayment(orderId, userId, channel);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("paymentNo", paymentNo)));
    }

    @PostMapping("/payments/callbacks/mock-pay")
    @Operation(summary = "模拟支付回调", description = "支付回调入口。验签、金额校验、幂等处理。")
    public ResponseEntity<ApiResponse<Void>> paymentCallback(
            @Valid @RequestBody PaymentCallbackRequest request) {
        paymentService.handleCallback(request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
