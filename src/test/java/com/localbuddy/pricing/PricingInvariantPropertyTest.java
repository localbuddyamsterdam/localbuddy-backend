package com.localbuddy.pricing;

import com.localbuddy.experience.PriceInputMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the engine is correct across the whole input space, not just enumerated
 * cases: 50,000 random combinations of price (incl. odd/large), VAT rate, host
 * VAT status, net/gross mode, commission and service-fee rates. Every one must
 * satisfy the accounting identities exactly.
 */
class PricingInvariantPropertyTest {

    private final PricingCalculator calculator = new PricingCalculator();

    private static final BigDecimal[] VAT_RATES = {
            bd("0.00"), bd("0.05"), bd("0.06"), bd("0.07"), bd("0.09"), bd("0.10"),
            bd("0.12"), bd("0.19"), bd("0.20"), bd("0.21"), bd("0.23"), bd("0.25"), bd("0.27")
    };
    private static final HostVatStatus[] STATUSES = HostVatStatus.values();
    private static final PriceInputMode[] MODES = PriceInputMode.values();

    @Test
    @DisplayName("accounting identities hold for 50,000 random pricing inputs")
    void invariantsHoldEverywhere() {
        Random rnd = new Random(20260621L); // fixed seed -> reproducible

        for (int i = 0; i < 50_000; i++) {
            HostVatStatus status = STATUSES[rnd.nextInt(STATUSES.length)];
            PriceInputMode mode = MODES[rnd.nextInt(MODES.length)];
            BigDecimal expVat = VAT_RATES[rnd.nextInt(VAT_RATES.length)];
            BigDecimal feeVat = VAT_RATES[rnd.nextInt(VAT_RATES.length)];
            // price 0.00 .. 99,999.99
            BigDecimal price = BigDecimal.valueOf(rnd.nextInt(10_000_000)).movePointLeft(2);
            // commission 0.0000 .. 0.5000 (a sane operating range; even at 50% +
            // 27% VAT-on-commission the host payout stays non-negative)
            BigDecimal commissionRate = BigDecimal.valueOf(rnd.nextInt(5001)).movePointLeft(4);
            // service fee 0.0000 .. 0.3000
            BigDecimal serviceFeeRate = BigDecimal.valueOf(rnd.nextInt(3001)).movePointLeft(4);

            PricingInput in = new PricingInput(status, expVat, mode, price, commissionRate, serviceFeeRate, feeVat);
            PricingBreakdown b = calculator.compute(in);

            String ctx = "i=" + i + " " + in;

            // 1. experience net + VAT == gross
            assertEquals(0, b.experienceNet().add(b.experienceVat()).compareTo(b.experienceGross()),
                    "net+vat != gross @ " + ctx);

            // 2. customer total == experience gross + service fee + its VAT
            assertEquals(0, b.customerTotal()
                            .compareTo(b.experienceGross().add(b.serviceFee()).add(b.serviceFeeVat())),
                    "customerTotal composition @ " + ctx);

            // 3. cash conservation: customer total == host payout + platform margin + VAT remitted
            BigDecimal sum = b.hostPayoutCash().add(b.platformKeeps()).add(b.platformRemitsVat());
            assertEquals(0, b.customerTotal().compareTo(sum), "cash-conservation @ " + ctx + " sum=" + sum);

            // 4. platform margin == commission + service fee; VAT remitted == commission VAT + fee VAT
            assertEquals(0, b.platformKeeps().compareTo(b.commission().add(b.serviceFee())),
                    "platformKeeps @ " + ctx);
            assertEquals(0, b.platformRemitsVat().compareTo(b.commissionVat().add(b.serviceFeeVat())),
                    "platformRemitsVat @ " + ctx);

            // 5. no negative money, and everything is 2-dp
            for (BigDecimal v : new BigDecimal[]{b.experienceGross(), b.experienceNet(), b.experienceVat(),
                    b.commission(), b.commissionVat(), b.serviceFee(), b.serviceFeeVat(),
                    b.customerTotal(), b.hostPayoutCash(), b.platformKeeps(), b.platformRemitsVat()}) {
                assertTrue(v.signum() >= 0, "negative amount " + v + " @ " + ctx);
                assertTrue(v.scale() <= 2, "scale > 2 (" + v + ") @ " + ctx);
            }

            // 6. host carries experience VAT only when registered
            boolean hostCarriesVat = status == HostVatStatus.NL_REGISTERED || status == HostVatStatus.EU_OTHER_REGISTERED;
            if (!hostCarriesVat) {
                assertEquals(0, b.experienceVat().signum(), "non-registered host should have no experience VAT @ " + ctx);
            }
            // commission VAT charged only for NL hosts (registered or not)
            boolean commissionVatCharged = status == HostVatStatus.NL_REGISTERED || status == HostVatStatus.NL_NOT_REGISTERED;
            if (!commissionVatCharged) {
                assertEquals(0, b.commissionVat().signum(), "no commission VAT for reverse-charge/out-of-scope @ " + ctx);
            }
        }
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
