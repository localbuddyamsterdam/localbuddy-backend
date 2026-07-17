package com.localbuddy.pricing;

import java.math.BigDecimal;

/**
 * Customer-facing service-fee configuration, in percent (e.g. 2.50 = 2.5%).
 * {@code feeVatPct} is the home-country standard VAT applied on top of the fee
 * itself — the effective surcharge is {@code serviceFeePct * (1 + feeVatPct/100)}.
 */
public record PublicServiceFeeResponse(
        BigDecimal serviceFeePct,
        BigDecimal feeVatPct
) {
}
