package com.minidb.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(
    @NotBlank @Size(max = 256) String reason
) {}
