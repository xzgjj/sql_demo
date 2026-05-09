package com.minidb.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveExceptionRequest(
    @NotBlank @Size(max = 512) String resolution
) {}
