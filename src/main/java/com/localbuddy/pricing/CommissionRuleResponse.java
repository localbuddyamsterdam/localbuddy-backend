package com.localbuddy.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CommissionRuleResponse(
        UUID id,
        ScopeType scopeType,
        UUID scopeId,
        BigDecimal rate,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active,
        String note
) {
}
