package com.localbuddy.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * In-place edit of an existing service-fee rule. Scope (scopeType/scopeId) is
 * immutable — create a new rule to change scope. Rate 0.00 is allowed (fee-free
 * cities/experiences).
 */
public record UpdateServiceFeeRuleRequest(
        @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal rate,
        Instant effectiveFrom,
        Instant effectiveTo,
        String note,
        Boolean active
) {
}
