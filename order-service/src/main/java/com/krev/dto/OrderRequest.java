package com.krev.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderRequest(@NotBlank(message = "product is required") String product,
                           @NotNull(message = "price is required")
                           @Positive(message = "price must be positive")
                           BigDecimal price) {
}
