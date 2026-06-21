package com.localbuddy.pricing;

import com.localbuddy.experience.PriceInputMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PricingCalculator} to the adversarially-verified golden vectors
 * (10 scenarios, every one passing the cash-conservation invariant).
 */
class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    private record Case(
            String id, HostVatStatus status, String vatRate, PriceInputMode mode, String price, String commRate,
            String gross, String net, String expVat, String comm, String commVat, CommissionVatTreatment treatment,
            String svc, String svcVat, String cust, String host, String keeps, String remit
    ) {
    }

    private static final List<Case> GOLDEN = List.of(
            new Case("S1", HostVatStatus.NL_NOT_REGISTERED, "0.21", PriceInputMode.GROSS, "100", "0.20",
                    "100.00", "100.00", "0.00", "20.00", "4.20", CommissionVatTreatment.NOT_REGISTERED,
                    "2.50", "0.53", "103.03", "75.80", "22.50", "4.73"),
            new Case("S2", HostVatStatus.NL_REGISTERED, "0.21", PriceInputMode.GROSS, "100", "0.20",
                    "100.00", "82.64", "17.36", "20.00", "4.20", CommissionVatTreatment.STANDARD,
                    "2.50", "0.53", "103.03", "75.80", "22.50", "4.73"),
            new Case("S3", HostVatStatus.NL_REGISTERED, "0.21", PriceInputMode.NET, "100", "0.20",
                    "121.00", "100.00", "21.00", "24.20", "5.08", CommissionVatTreatment.STANDARD,
                    "3.03", "0.64", "124.67", "91.72", "27.23", "5.72"),
            new Case("S4", HostVatStatus.NL_REGISTERED, "0.09", PriceInputMode.GROSS, "100", "0.20",
                    "100.00", "91.74", "8.26", "20.00", "4.20", CommissionVatTreatment.STANDARD,
                    "2.50", "0.53", "103.03", "75.80", "22.50", "4.73"),
            new Case("S5", HostVatStatus.NL_REGISTERED, "0.21", PriceInputMode.GROSS, "100", "0.15",
                    "100.00", "82.64", "17.36", "15.00", "3.15", CommissionVatTreatment.STANDARD,
                    "2.50", "0.53", "103.03", "81.85", "17.50", "3.68"),
            new Case("S6", HostVatStatus.NL_REGISTERED, "0.21", PriceInputMode.GROSS, "100", "0.10",
                    "100.00", "82.64", "17.36", "10.00", "2.10", CommissionVatTreatment.STANDARD,
                    "2.50", "0.53", "103.03", "87.90", "12.50", "2.63"),
            new Case("S7", HostVatStatus.EU_OTHER_REGISTERED, "0.21", PriceInputMode.GROSS, "100", "0.20",
                    "100.00", "82.64", "17.36", "20.00", "0.00", CommissionVatTreatment.REVERSE_CHARGE,
                    "2.50", "0.53", "103.03", "80.00", "22.50", "0.53"),
            new Case("S8", HostVatStatus.NL_NOT_REGISTERED, "0.21", PriceInputMode.NET, "100", "0.20",
                    "100.00", "100.00", "0.00", "20.00", "4.20", CommissionVatTreatment.NOT_REGISTERED,
                    "2.50", "0.53", "103.03", "75.80", "22.50", "4.73"),
            new Case("S9", HostVatStatus.NL_REGISTERED, "0.21", PriceInputMode.GROSS, "83.33", "0.20",
                    "83.33", "68.87", "14.46", "16.67", "3.50", CommissionVatTreatment.STANDARD,
                    "2.08", "0.44", "85.85", "63.16", "18.75", "3.94"),
            new Case("S10", HostVatStatus.NL_NOT_REGISTERED, "0.09", PriceInputMode.GROSS, "49.99", "0.20",
                    "49.99", "49.99", "0.00", "10.00", "2.10", CommissionVatTreatment.NOT_REGISTERED,
                    "1.25", "0.26", "51.50", "37.89", "11.25", "2.36")
    );

    @Test
    @DisplayName("calculator reproduces every adversarially-verified golden vector")
    void matchesGoldenVectors() {
        for (Case c : GOLDEN) {
            PricingInput in = new PricingInput(
                    c.status(), new BigDecimal(c.vatRate()), c.mode(),
                    new BigDecimal(c.price()), new BigDecimal(c.commRate()),
                    new BigDecimal("0.025"), new BigDecimal("0.21"));

            PricingBreakdown b = calculator.compute(in);

            money(c.id(), "experienceGross", c.gross(), b.experienceGross());
            money(c.id(), "experienceNet", c.net(), b.experienceNet());
            money(c.id(), "experienceVat", c.expVat(), b.experienceVat());
            money(c.id(), "commission", c.comm(), b.commission());
            money(c.id(), "commissionVat", c.commVat(), b.commissionVat());
            assertEquals(c.treatment(), b.commissionVatTreatment(), c.id() + " commissionVatTreatment");
            money(c.id(), "serviceFee", c.svc(), b.serviceFee());
            money(c.id(), "serviceFeeVat", c.svcVat(), b.serviceFeeVat());
            money(c.id(), "customerTotal", c.cust(), b.customerTotal());
            money(c.id(), "hostPayoutCash", c.host(), b.hostPayoutCash());
            money(c.id(), "platformKeeps", c.keeps(), b.platformKeeps());
            money(c.id(), "platformRemitsVat", c.remit(), b.platformRemitsVat());

            // Cash conservation: customer total == host + platform margin + VAT remitted.
            BigDecimal sum = b.hostPayoutCash().add(b.platformKeeps()).add(b.platformRemitsVat());
            assertTrue(b.customerTotal().subtract(sum).abs().compareTo(new BigDecimal("0.02")) <= 0,
                    c.id() + " cash-conservation invariant broken: customerTotal=" + b.customerTotal() + " sum=" + sum);
        }
    }

    private static void money(String id, String field, String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                id + " " + field + " expected=" + expected + " actual=" + actual);
    }
}
