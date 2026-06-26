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
        BigDecimal feeVatRate,
        /**
         * The gross price the host's commission and payout are computed on. When null it
         * defaults to the customer experience gross (the host bears any discount — legacy
         * behaviour). When the platform absorbs part/all of a discount, this is higher than
         * the customer price, so the host still earns on the un-absorbed portion.
         */
        BigDecimal hostExperienceGross
) {
    /** Legacy form: the host earns on the same gross the customer pays. */
    public PricingInput(HostVatStatus hostVatStatus, BigDecimal experienceVatRate, PriceInputMode priceInputMode,
                        BigDecimal enteredPrice, BigDecimal commissionRate, BigDecimal serviceFeeRate,
                        BigDecimal feeVatRate) {
        this(hostVatStatus, experienceVatRate, priceInputMode, enteredPrice, commissionRate, serviceFeeRate,
                feeVatRate, null);
    }
}
