package com.localbuddy.pricing;

import java.math.BigDecimal;

/**
 * The full, immutable financial breakdown of a booking. Every euro the customer
 * pays is conserved: customerTotal == hostPayoutCash + platformKeeps + platformRemitsVat.
 */
public record PricingBreakdown(
        BigDecimal experienceGross,
        BigDecimal experienceNet,
        BigDecimal experienceVat,
        BigDecimal experienceVatRate,
        BigDecimal commission,
        BigDecimal commissionRate,
        BigDecimal commissionVat,
        CommissionVatTreatment commissionVatTreatment,
        BigDecimal serviceFee,
        BigDecimal serviceFeeRate,
        BigDecimal serviceFeeVat,
        BigDecimal customerTotal,
        BigDecimal hostPayoutCash,
        BigDecimal platformKeeps,
        BigDecimal platformRemitsVat
) {
}
