package com.minidb.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateOrderResponse(
    Long orderId,
    String orderNo,
    int status,
    BigDecimal totalAmount,
    LocalDateTime expiresAt
) {}
