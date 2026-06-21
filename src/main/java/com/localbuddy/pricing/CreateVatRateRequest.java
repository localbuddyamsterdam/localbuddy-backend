package com.localbuddy.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateVatRateRequest(
        @NotBlank String country,
        UUID categoryId,
        @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal rate,
        String rateKind,
        String description,
        Instant effectiveFrom,
        Instant effectiveTo
) {
}
