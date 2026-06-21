package com.localbuddy.pricing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateCommissionRuleRequest(
        @NotNull ScopeType scopeType,
        UUID scopeId,
        @NotNull @DecimalMin("0.00") BigDecimal rate,
        Instant effectiveFrom,
        Instant effectiveTo,
        String note
) {
}
