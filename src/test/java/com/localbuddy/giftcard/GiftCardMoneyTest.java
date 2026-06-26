package com.localbuddy.giftcard;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pins gift-card-as-payment math: apply min(balance, due), deplete at zero, return/reactivate. */
class GiftCardMoneyTest {

    private final GiftCardRepository giftCardRepository = mock(GiftCardRepository.class);
    private final GiftCardRedemptionRepository redemptionRepository = mock(GiftCardRedemptionRepository.class);
    private final GiftCardBookingRepository bookingRepository = mock(GiftCardBookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final com.localbuddy.payment.PaymentCheckoutProvider paymentCheckoutProvider =
            mock(com.localbuddy.payment.PaymentCheckoutProvider.class);
    private final GiftCardService service = new GiftCardService(
            giftCardRepository, redemptionRepository, bookingRepository, userRepository, paymentCheckoutProvider, 365);

    private GiftCard card(String balance, GiftCardStatus status) {
        GiftCard c = new GiftCard();
        c.setId(UUID.randomUUID());
        c.setCode("LB-AAAA-BBBB-CCCC");
        c.setInitialAmount(new BigDecimal("100.00"));
        c.setBalance(new BigDecimal(balance));
        c.setStatus(status);
        when(giftCardRepository.findByCode(any())).thenReturn(Optional.of(c));
        when(giftCardRepository.findByIdForUpdate(eq(c.getId()))).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("partial: applies min(balance, due), leaves the remainder")
    void partialReserve() {
        GiftCard c = card("100.00", GiftCardStatus.ACTIVE);
        GiftCardApplication app = service.reserveForCheckout("LB-AAAA-BBBB-CCCC", new BigDecimal("30.00"));
        assertEquals(new BigDecimal("30.00"), app.amount());
        assertEquals(new BigDecimal("70.00"), c.getBalance());
        assertEquals(GiftCardStatus.ACTIVE, c.getStatus());
    }

    @Test
    @DisplayName("booking exceeds balance: applies full balance, card depletes")
    void fullReserveDepletes() {
        GiftCard c = card("40.00", GiftCardStatus.ACTIVE);
        GiftCardApplication app = service.reserveForCheckout("LB-AAAA-BBBB-CCCC", new BigDecimal("150.00"));
        assertEquals(new BigDecimal("40.00"), app.amount());
        assertEquals(new BigDecimal("0.00"), c.getBalance());
        assertEquals(GiftCardStatus.DEPLETED, c.getStatus());
    }

    @Test
    @DisplayName("return reactivates a depleted card")
    void returnReactivates() {
        GiftCard c = card("0.00", GiftCardStatus.DEPLETED);
        service.returnToCard(c.getId(), new BigDecimal("40.00"));
        assertEquals(new BigDecimal("40.00"), c.getBalance());
        assertEquals(GiftCardStatus.ACTIVE, c.getStatus());
    }

    @Test
    @DisplayName("cannot reserve from a non-active card")
    void nonActiveRejected() {
        card("100.00", GiftCardStatus.PENDING_PAYMENT);
        assertThrows(BadRequestException.class,
                () -> service.reserveForCheckout("LB-AAAA-BBBB-CCCC", new BigDecimal("10.00")));
    }
}
