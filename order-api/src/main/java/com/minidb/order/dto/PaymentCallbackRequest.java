package com.minidb.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentCallbackRequest(
    @NotBlank @Size(max = 64) String paymentNo,
    @Size(max = 128) String channelTradeNo,
    @NotNull @Positive BigDecimal amount,
    @NotNull LocalDateTime paidAt,
    @NotBlank @Size(max = 32) String status,
    @NotBlank @Size(max = 256) String signature
) {}
