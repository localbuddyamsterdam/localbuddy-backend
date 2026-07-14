package com.localbuddy.referral;

import java.math.BigDecimal;

/**
 * Outcome of applying a referral code to a booking: the resolved code (null when
 * none) and the reward amount in force — the same value used for the referred
 * user's discount and, later, the referrer's reward voucher.
 */
public record AppliedReferralCode(
        ReferralCode referralCode,
        BigDecimal rewardAmount
) {
    public static AppliedReferralCode none() {
        return new AppliedReferralCode(null, BigDecimal.ZERO);
    }
}
