package com.localbuddy.pricing;

import com.localbuddy.experience.PriceInputMode;

import java.math.BigDecimal;

/**
 * The resolved raw inputs for a single pricing computation. Rates are decimals
 * (0.21 = 21%). {@code enteredPrice} is the experience price either gross or net
 * per {@code priceInputMode}.
 */
public record PricingInput(
        HostVatStatus hostVatStatus,
        BigDecimal experienceVatRate,
        PriceInputMode priceInputMode,
        BigDecimal enteredPrice,
        BigDecimal commissionRate,
        BigDecimal serviceFeeRate,
        BigDecimal feeVatRate
) {
}
