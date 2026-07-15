package com.localbuddy.experience;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin bulk commission override: applies the same {@code commissionRate} (fraction,
 * e.g. 0.15 = 15%) to every listed experience. {@code null} rate clears the override
 * on all of them so rules/default apply again.
 */
public record BulkSetExperienceCommissionRequest(
        @NotEmpty(message = "At least one experience id is required")
        List<UUID> experienceIds,

        BigDecimal commissionRate
) {
}
