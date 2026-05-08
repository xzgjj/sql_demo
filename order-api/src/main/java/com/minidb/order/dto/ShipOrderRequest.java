package com.minidb.order.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipOrderRequest(
    @NotBlank String carrier,
    @NotBlank String trackingNo
) {}
