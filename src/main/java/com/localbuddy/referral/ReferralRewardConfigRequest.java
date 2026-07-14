package com.localbuddy.referral;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Admin create/update payload for the single referral reward configuration. */
public record ReferralRewardConfigRequest(

        @NotNull(message = "Reward amount is required")
        @DecimalMin(value = "0.0", message = "Reward amount cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "Reward amount is invalid")
        BigDecimal rewardAmount,

        @Size(max = 10, message = "Currency cannot exceed 10 characters")
        String rewardCurrency,

        /** NULL = effective immediately. */
        Instant startsAt,

        /** NULL = no expiry. */
        Instant endsAt,

        @Min(value = 1, message = "Monthly cap must be at least 1")
        Integer maxMonthlyRedemptions,

        Boolean active
) {
}
