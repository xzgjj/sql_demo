package com.minidb.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
    @NotNull Long userId,
    @NotEmpty @Size(min = 1, max = 20) @Valid List<OrderItemRequest> items,
    @Size(max = 256) String remark
) {
    public record OrderItemRequest(
        @NotNull Long productId,
        @Positive int quantity
    ) {}
}
