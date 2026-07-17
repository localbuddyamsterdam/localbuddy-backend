package com.localbuddy.booking;

import com.localbuddy.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Resolves party composition into seat count + billable pricing units.
 *
 * <p><b>Seats (capacity) count adults and teens only</b> — children and infants ride
 * along on a companion's seat and never consume slot capacity (see {@link AgeBands#seats()}).
 * Price is weighted independently per age band: adults &amp; teens full price, children
 * half, infants free (all configurable via {@code app.pricing.age-band.*}). The teen
 * band exists primarily to age-gate 18+ experiences, not to discount.
 */
@Component
public class AgeBandPricing {

    // Representative upper age per band, used only for age-gating against an experience's minimum age.
    private static final int TEEN_MAX_AGE = 17;
    private static final int CHILD_MAX_AGE = 12;
    private static final int INFANT_MAX_AGE = 2;

    private final BigDecimal adultRate;
    private final BigDecimal teenRate;
    private final BigDecimal childRate;
    private final BigDecimal infantRate;

    public AgeBandPricing(
            @Value("${app.pricing.age-band.adult-rate:1.0}") BigDecimal adultRate,
            @Value("${app.pricing.age-band.teen-rate:1.0}") BigDecimal teenRate,
            @Value("${app.pricing.age-band.child-rate:0.5}") BigDecimal childRate,
            @Value("${app.pricing.age-band.infant-rate:0.0}") BigDecimal infantRate) {
        this.adultRate = adultRate;
        this.teenRate = teenRate;
        this.childRate = childRate;
        this.infantRate = infantRate;
    }

    public record AgeBands(int adults, int teens, int children, int infants, int totalGuests) {
        /**
         * Seats consumed against a slot's capacity. Only adults and teens take a seat;
         * children and infants ride along and never reduce the remaining count.
         */
        public int seats() {
            return adults + teens;
        }
    }

    /**
     * Resolves the requested bands. When no band counts are supplied, falls back to
     * treating the whole {@code guestsCount} as adults (backward compatible).
     */
    public AgeBands resolve(Integer adults, Integer teens, Integer children, Integer infants, Integer guestsCount) {
        boolean anyBand = adults != null || teens != null || children != null || infants != null;
        if (!anyBand) {
            int total = guestsCount == null ? 0 : guestsCount;
            return new AgeBands(total, 0, 0, 0, total);
        }
        int a = nonNegative(adults);
        int t = nonNegative(teens);
        int c = nonNegative(children);
        int i = nonNegative(infants);
        int total = a + t + c + i;
        if (total < 1) {
            throw new BadRequestException("At least one guest is required");
        }
        return new AgeBands(a, t, c, i, total);
    }

    /** Weighted pricing units: adults+teens at full, children half, infants free (by default). */
    public BigDecimal billableUnits(AgeBands bands) {
        return adultRate.multiply(BigDecimal.valueOf(bands.adults()))
                .add(teenRate.multiply(BigDecimal.valueOf(bands.teens())))
                .add(childRate.multiply(BigDecimal.valueOf(bands.children())))
                .add(infantRate.multiply(BigDecimal.valueOf(bands.infants())));
    }

    /** Rejects bands too young for an experience's minimum age (e.g. teens on an 18+ tour). */
    public void validateAgeGate(Integer minimumAge, AgeBands bands) {
        int min = minimumAge == null ? 0 : minimumAge;
        if (min > TEEN_MAX_AGE && bands.teens() > 0) {
            throw new BadRequestException("This experience does not allow teenage guests (minimum age " + min + ")");
        }
        if (min > CHILD_MAX_AGE && bands.children() > 0) {
            throw new BadRequestException("This experience does not allow child guests (minimum age " + min + ")");
        }
        if (min > INFANT_MAX_AGE && bands.infants() > 0) {
            throw new BadRequestException("This experience does not allow infant guests (minimum age " + min + ")");
        }
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
