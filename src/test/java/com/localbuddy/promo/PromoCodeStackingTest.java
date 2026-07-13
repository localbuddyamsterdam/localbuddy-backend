package com.localbuddy.promo;

import com.localbuddy.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pins voucher/promo stacking: percentage codes first, then fixed; combinable rules. */
class PromoCodeStackingTest {

    private final PromoCodeRepository promoCodeRepository = mock(PromoCodeRepository.class);
    private final PromoCodeRedemptionRepository redemptionRepository = mock(PromoCodeRedemptionRepository.class);
    private final PromoCodeService service = new PromoCodeService(promoCodeRepository, redemptionRepository);

    private PromoCode code(String code, PromoDiscountType type, String value, boolean combinable) {
        PromoCode pc = new PromoCode();
        pc.setId(UUID.randomUUID());
        pc.setCode(code);
        pc.setDiscountType(type);
        pc.setDiscountValue(new BigDecimal(value));
        pc.setActive(true);
        pc.setCombinable(combinable);
        when(promoCodeRepository.findByCodeIgnoreCase(eq(code))).thenReturn(Optional.of(pc));
        return pc;
    }

    @Test
    @DisplayName("percentage applies before fixed, regardless of input order")
    void percentageFirstThenFixed() {
        code("TEN", PromoDiscountType.FIXED_AMOUNT, "10", true);
        code("PCT20", PromoDiscountType.PERCENTAGE, "20", true);

        AppliedPromoCodes r = service.applyPromoCodesForBooking(
                UUID.randomUUID(), List.of("TEN", "PCT20"), null, new BigDecimal("100.00"), "EUR", null);

        // 20% off 100 -> 80, then 10 off -> 70 (even though "TEN" was listed first)
        assertEquals(new BigDecimal("70.00"), r.finalAmount());
        assertEquals(new BigDecimal("30.00"), r.totalDiscount());
        assertEquals(PromoDiscountType.PERCENTAGE, r.codes().get(0).promoCode().getDiscountType());
    }

    @Test
    @DisplayName("non-combinable codes cannot be stacked together")
    void nonCombinableCannotStack() {
        code("A", PromoDiscountType.PERCENTAGE, "10", true);
        code("B", PromoDiscountType.FIXED_AMOUNT, "5", false);

        assertThrows(BadRequestException.class, () -> service.applyPromoCodesForBooking(
                UUID.randomUUID(), List.of("A", "B"), null, new BigDecimal("100.00"), "EUR", null));
    }

    @Test
    @DisplayName("a single non-combinable code is fine on its own")
    void singleNonCombinableOk() {
        code("SOLO", PromoDiscountType.PERCENTAGE, "25", false);

        AppliedPromoCodes r = service.applyPromoCodesForBooking(
                UUID.randomUUID(), List.of("SOLO"), null, new BigDecimal("100.00"), "EUR", null);

        assertEquals(new BigDecimal("75.00"), r.finalAmount());
        assertEquals(1, r.codes().size());
    }

    @Test
    @DisplayName("no codes leaves the amount unchanged")
    void noCodes() {
        AppliedPromoCodes r = service.applyPromoCodesForBooking(
                UUID.randomUUID(), List.of(), null, new BigDecimal("100.00"), "EUR", null);

        assertEquals(new BigDecimal("100.00"), r.finalAmount());
        assertEquals(new BigDecimal("0.00"), r.totalDiscount());
        assertTrue(r.codes().isEmpty());
    }
}
