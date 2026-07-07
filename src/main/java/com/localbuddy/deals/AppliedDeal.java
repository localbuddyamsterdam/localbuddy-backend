package com.localbuddy.deals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The deal (if any) applied to a booking's price. {@link #none()} represents
 * "no deal" with a zero discount.
 */
public record AppliedDeal(UUID dealId, BigDecimal discountAmount, String badgeText) {

    public static AppliedDeal none() {
        return new AppliedDeal(null, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), null);
    }
}
