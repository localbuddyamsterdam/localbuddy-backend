package com.localbuddy.pricing;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingPromoCode;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.PriceInputMode;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.payment.Payment;
import com.localbuddy.promo.PromoCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Resolves all rates for a booking and writes the full financial breakdown onto a
 * payment. {@code booking.totalAmount} is the gross (customer-facing) experience
 * price after any promo/referral discounts; the service fee is added on top, so
 * the customer is charged {@code experienceGross + serviceFee + serviceFeeVat}.
 */
@Service
public class PricingEngine {

    private final PricingCalculator calculator;
    private final CommissionResolver commissionResolver;
    private final ServiceFeeResolver serviceFeeResolver;
    private final VatService vatService;

    public PricingEngine(PricingCalculator calculator,
                         CommissionResolver commissionResolver,
                         ServiceFeeResolver serviceFeeResolver,
                         VatService vatService) {
        this.calculator = calculator;
        this.commissionResolver = commissionResolver;
        this.serviceFeeResolver = serviceFeeResolver;
        this.vatService = vatService;
    }

    /** Computes the breakdown for a booking (no persistence) — useful for previews. */
    public PricingBreakdown priceBooking(Booking booking) {
        return calculator.compute(buildInput(booking));
    }

    /** Computes the breakdown and writes every snapshot field onto the payment. */
    public void applyTo(Payment payment, Booking booking) {
        Experience experience = booking.getExperience();
        LocalProfile host = booking.getLocalProfile();
        Instant now = Instant.now();

        String placeOfSupply = vatService.placeOfSupply(experience, host);
        BigDecimal feeVatRate = vatService.feeVatRate(now);
        HostVatStatus hostVatStatus = vatService.hostVatStatus(host);
        PricingBreakdown b = calculator.compute(buildInput(booking));

        payment.setExperienceGrossAmount(b.experienceGross());
        payment.setExperienceNetAmount(b.experienceNet());
        payment.setExperienceVatRate(b.experienceVatRate());
        payment.setExperienceVatAmount(b.experienceVat());

        payment.setCommissionRate(b.commissionRate());
        payment.setCommissionAmount(b.commission());
        boolean commissionVatCharged = hostVatStatus == HostVatStatus.NL_REGISTERED
                || hostVatStatus == HostVatStatus.NL_NOT_REGISTERED;
        payment.setCommissionVatRate(commissionVatCharged ? feeVatRate : BigDecimal.ZERO);
        payment.setCommissionVatAmount(b.commissionVat());
        payment.setCommissionVatTreatment(b.commissionVatTreatment().name());

        payment.setServiceFeeAmount(b.serviceFee());
        payment.setServiceFeeVatAmount(b.serviceFeeVat());

        payment.setPlaceOfSupplyCountry(placeOfSupply);
        payment.setHostPayoutAmount(b.hostPayoutCash());

        // What the customer is actually charged, and the legacy split fields.
        payment.setAmount(b.customerTotal());
        payment.setCurrency(booking.getCurrency());
        payment.setPlatformFeeAmount(b.platformKeeps());
        payment.setLocalPayoutAmount(b.hostPayoutCash());
    }

    private PricingInput buildInput(Booking booking) {
        Experience experience = booking.getExperience();
        LocalProfile host = booking.getLocalProfile();
        Instant now = Instant.now();

        String placeOfSupply = vatService.placeOfSupply(experience, host);
        BigDecimal feeVatRate = vatService.feeVatRate(now);
        BigDecimal experienceVatRate = vatService.experienceVatRate(experience, placeOfSupply, now);
        HostVatStatus hostVatStatus = vatService.hostVatStatus(host);
        BigDecimal commissionRate = commissionResolver.resolve(experience, host, now);
        BigDecimal serviceFeeRate = serviceFeeResolver.resolve(experience, host, now);

        BigDecimal experienceGross = booking.getTotalAmount() != null ? booking.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal hostExperienceGross = experienceGross.add(platformBorneDiscount(booking));

        return new PricingInput(
                hostVatStatus, experienceVatRate, PriceInputMode.GROSS,
                experienceGross, commissionRate, serviceFeeRate, feeVatRate, hostExperienceGross);
    }

    /**
     * The portion of a booking's discount the platform absorbs, so the host still earns on it.
     * HOST-borne discounts (and bookings with no promo) return zero, preserving legacy behaviour.
     */
    private BigDecimal platformBorneDiscount(Booking booking) {
        List<BookingPromoCode> applied = booking.getAppliedPromoCodes();
        if (applied != null && !applied.isEmpty()) {
            BigDecimal total = BigDecimal.ZERO;
            for (BookingPromoCode code : applied) {
                total = total.add(platformShareOf(code.getPromoCode(), code.getDiscountAmount()));
            }
            return total;
        }
        // Fallback for bookings created before multi-code stacking (single promo + total discount).
        return platformShareOf(booking.getPromoCode(), booking.getDiscountAmount());
    }

    private BigDecimal platformShareOf(PromoCode promo, BigDecimal discount) {
        if (promo == null || discount == null || discount.signum() <= 0 || promo.getDiscountBearer() == null) {
            return BigDecimal.ZERO;
        }
        return switch (promo.getDiscountBearer()) {
            case HOST -> BigDecimal.ZERO;
            case PLATFORM -> discount;
            case SPLIT -> discount
                    .multiply(promo.getPlatformSharePercentage() != null
                            ? promo.getPlatformSharePercentage() : BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        };
    }
}
