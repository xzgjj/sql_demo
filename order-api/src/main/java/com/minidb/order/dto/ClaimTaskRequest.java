package com.minidb.order.dto;

import jakarta.validation.constraints.NotNull;

public record ClaimTaskRequest(
    @NotNull Long userId,
    @NotNull Long taskId
) {}
