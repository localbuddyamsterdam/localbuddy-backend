package com.localbuddy.pricing;

import com.localbuddy.experience.PriceInputMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, dependency-free commission + VAT + host-payout calculator. Every line is
 * rounded HALF_UP to 2 decimals before summing. This is verified against the
 * adversarially-checked golden vectors in PricingCalculatorTest.
 *
 * <p>Cash-conservation invariant (always holds within rounding):
 * customerTotal == hostPayoutCash + platformKeeps + platformRemitsVat.
 */
@Component
public class PricingCalculator {

    public PricingBreakdown compute(PricingInput in) {
        BigDecimal feeVatRate = nz(in.feeVatRate());
        BigDecimal commissionRate = nz(in.commissionRate());
        BigDecimal serviceFeeRate = nz(in.serviceFeeRate());

        // A non-registered or non-EU host charges no VAT on the experience itself.
        boolean hostCarriesVat = in.hostVatStatus() == HostVatStatus.NL_REGISTERED
                || in.hostVatStatus() == HostVatStatus.EU_OTHER_REGISTERED;
        BigDecimal effectiveExpVat = hostCarriesVat ? nz(in.experienceVatRate()) : BigDecimal.ZERO;

        // Step 1 — experience gross/net/VAT.
        BigDecimal experienceGross;
        BigDecimal experienceNet;
        BigDecimal experienceVat;
        if (in.priceInputMode() == PriceInputMode.NET) {
            experienceNet = round(in.enteredPrice());
            experienceVat = round(experienceNet.multiply(effectiveExpVat));
            experienceGross = round(experienceNet.add(experienceVat));
        } else {
            experienceGross = round(in.enteredPrice());
            experienceNet = experienceGross.divide(BigDecimal.ONE.add(effectiveExpVat), 2, RoundingMode.HALF_UP);
            experienceVat = round(experienceGross.subtract(experienceNet));
        }

        // The host's commission/payout base. Defaults to the customer gross (the host bears
        // any discount); a higher value means the platform absorbs part/all of the discount.
        BigDecimal hostGross = in.hostExperienceGross() != null ? round(in.hostExperienceGross()) : experienceGross;
        BigDecimal platformBorneDiscount = round(hostGross.subtract(experienceGross)).max(BigDecimal.ZERO);

        // Step 2 — commission (base = host gross) + its VAT treatment.
        BigDecimal commission = round(hostGross.multiply(commissionRate));
        BigDecimal commissionVat;
        CommissionVatTreatment treatment;
        switch (in.hostVatStatus()) {
            case NL_REGISTERED -> {
                commissionVat = round(commission.multiply(feeVatRate));
                treatment = CommissionVatTreatment.STANDARD;
            }
            case NL_NOT_REGISTERED -> {
                commissionVat = round(commission.multiply(feeVatRate));
                treatment = CommissionVatTreatment.NOT_REGISTERED;
            }
            case EU_OTHER_REGISTERED -> {
                commissionVat = round(BigDecimal.ZERO);
                treatment = CommissionVatTreatment.REVERSE_CHARGE;
            }
            case NON_EU -> {
                commissionVat = round(BigDecimal.ZERO);
                treatment = CommissionVatTreatment.OUT_OF_SCOPE;
            }
            default -> throw new IllegalArgumentException("Unknown host VAT status: " + in.hostVatStatus());
        }

        // Step 3 — customer service fee (base = experience gross) + its VAT.
        BigDecimal serviceFee = round(experienceGross.multiply(serviceFeeRate));
        BigDecimal serviceFeeVat = round(serviceFee.multiply(feeVatRate));

        // Step 4 — settlement. The platform's margin absorbs any platform-borne discount.
        BigDecimal customerTotal = round(experienceGross.add(serviceFee).add(serviceFeeVat));
        BigDecimal hostPayoutCash = round(hostGross.subtract(commission).subtract(commissionVat));
        BigDecimal platformKeeps = round(commission.add(serviceFee).subtract(platformBorneDiscount));
        BigDecimal platformRemitsVat = round(commissionVat.add(serviceFeeVat));

        return new PricingBreakdown(
                experienceGross, experienceNet, experienceVat, nz(in.experienceVatRate()),
                commission, commissionRate, commissionVat, treatment,
                serviceFee, serviceFeeRate, serviceFeeVat,
                customerTotal, hostPayoutCash, platformKeeps, platformRemitsVat
        );
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
