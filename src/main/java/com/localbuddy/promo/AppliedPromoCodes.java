package com.localbuddy.promo;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of applying one or more (stacked) promo/voucher codes to a booking.
 * {@code codes} holds the per-code breakdown in the order they were applied
 * (percentage codes first, then fixed-amount); {@code totalDiscount} is their sum
 * and {@code finalAmount} is the amount left after all of them (never below zero).
 */
public record AppliedPromoCodes(
        List<AppliedPromoCode> codes,
        BigDecimal totalDiscount,
        BigDecimal finalAmount
) {
}
