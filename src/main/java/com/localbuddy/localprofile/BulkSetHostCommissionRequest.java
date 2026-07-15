package com.localbuddy.localprofile;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin bulk commission override: applies the same {@code commissionRate} (fraction,
 * e.g. 0.15 = 15%) to every listed host profile. {@code null} rate clears the override
 * on all of them so rules/default apply again.
 */
public record BulkSetHostCommissionRequest(
        @NotEmpty(message = "At least one profile id is required")
        List<UUID> profileIds,

        BigDecimal commissionRate
) {
}
