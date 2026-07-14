package com.localbuddy.referral;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The referral reward configuration as seen by admins. {@code effectiveNow} +
 * {@code effectiveRewardAmount} report what is actually in force right now
 * (the config's amount when active and inside its window, else the default).
 */
public record ReferralRewardConfigResponse(
        UUID id,
        BigDecimal rewardAmount,
        String rewardCurrency,
        Instant startsAt,
        Instant endsAt,
        Integer maxMonthlyRedemptions,
        boolean active,
        boolean effectiveNow,
        BigDecimal effectiveRewardAmount,
        int effectiveMonthlyCap,
        BigDecimal defaultRewardAmount,
        int defaultMonthlyCap,
        Instant createdAt,
        Instant updatedAt
) {
}
