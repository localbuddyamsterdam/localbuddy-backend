package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingSource;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.giftcard.GiftCardApplication;
import com.localbuddy.giftcard.GiftCardService;
import com.localbuddy.pricing.PricingEngine;
import com.localbuddy.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the money math of bundle-checkout preparation: one gift-card draw against the GROUP
 * total, distributed across member payments in order and capped at each member's amount —
 * that per-member share is what later makes per-booking refunds exact. Also pins the
 * guards: uniform currency, no member already in another checkout, and owner checks that
 * 404 (not 403) on foreign bookings.
 */
class PaymentGroupPreparationTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final PricingEngine pricingEngine = mock(PricingEngine.class);
    private final GiftCardService giftCardService = mock(GiftCardService.class);
    private final PaymentGroupRepository paymentGroupRepository = mock(PaymentGroupRepository.class);

    private final PaymentTransactionService service = new PaymentTransactionService(
            paymentRepository, bookingRepository, pricingEngine, giftCardService, paymentGroupRepository);

    private final UUID userId = UUID.randomUUID();
    private final User user = user(userId);

    private User user(UUID id) {
        User u = new User();
        setField(u, "id", id);
        return u;
    }

    private void setField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Booking booking(String reference, String currency, BigDecimal amount) {
        Booking booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setBookingReference(reference);
        booking.setLoggedInUser(user);
        booking.setBookingSource(BookingSource.LOGGED_IN_USER);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        // New payments get their amount/currency from the pricing engine snapshot.
        doAnswer(inv -> {
            Payment payment = inv.getArgument(0);
            payment.setAmount(amount);
            payment.setCurrency(currency);
            return null;
        }).when(pricingEngine).applyTo(any(Payment.class), eq(booking));

        return booking;
    }

    private void stubIdentitySaves() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGroupRepository.save(any(PaymentGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(any(), anyList()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("one gift-card draw is distributed across members in order, capped per member")
    void giftCardDistribution() {
        stubIdentitySaves();
        Booking first = booking("LB-AAA", "EUR", new BigDecimal("60.00"));
        Booking second = booking("LB-BBB", "EUR", new BigDecimal("50.00"));
        UUID giftCardId = UUID.randomUUID();
        // Card balance covers 80 of the 110 total: 60 to the first member, 20 to the second.
        when(giftCardService.reserveForCheckout(eq("GIFT"), eq(new BigDecimal("110.00"))))
                .thenReturn(new GiftCardApplication(giftCardId, new BigDecimal("80.00")));

        GroupCheckoutPreparation preparation = service.prepareGroupForCheckout(
                userId, null, List.of(first.getId(), second.getId()), "GIFT", null);

        assertEquals(new BigDecimal("110.00"), preparation.group().getTotalAmount());
        assertEquals(new BigDecimal("80.00"), preparation.group().getGiftCardAmount());
        assertEquals(giftCardId, preparation.group().getGiftCardId());
        assertNotNull(preparation.group().getGroupToken());

        Payment firstMember = preparation.payments().get(0);
        Payment secondMember = preparation.payments().get(1);
        assertEquals(new BigDecimal("60.00"), firstMember.getGiftCardAmount(),
                "first member's share capped at its own amount");
        assertEquals(new BigDecimal("20.00"), secondMember.getGiftCardAmount(),
                "second member gets the remainder");
        assertEquals(preparation.group(), firstMember.getPaymentGroup());
        assertEquals(preparation.group(), secondMember.getPaymentGroup());
    }

    @Test
    @DisplayName("mixed currencies are rejected — one Stripe session has one currency")
    void currencyMismatchRejected() {
        stubIdentitySaves();
        Booking eur = booking("LB-AAA", "EUR", new BigDecimal("60.00"));
        Booking usd = booking("LB-BBB", "USD", new BigDecimal("50.00"));

        assertThrows(BadRequestException.class, () -> service.prepareGroupForCheckout(
                userId, null, List.of(eur.getId(), usd.getId()), null, null));
    }

    @Test
    @DisplayName("a member with a checkout already in progress blocks the bundle")
    void inProgressMemberRejected() {
        stubIdentitySaves();
        Booking booking = booking("LB-AAA", "EUR", new BigDecimal("60.00"));

        Payment processing = new Payment();
        processing.setBooking(booking);
        processing.setPaymentStatus(PaymentStatus.PROCESSING);
        processing.setAmount(new BigDecimal("60.00"));
        processing.setCurrency("EUR");
        when(paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
                eq(booking.getId()), anyList())).thenReturn(Optional.of(processing));

        assertThrows(BadRequestException.class, () -> service.prepareGroupForCheckout(
                userId, null, List.of(booking.getId()), null, null));
    }

    @Test
    @DisplayName("someone else's booking reads as 404, not 403 — no booking-id oracle")
    void foreignBookingIsNotFound() {
        stubIdentitySaves();
        Booking booking = booking("LB-AAA", "EUR", new BigDecimal("60.00"));

        assertThrows(ResourceNotFoundException.class, () -> service.prepareGroupForCheckout(
                UUID.randomUUID(), null, List.of(booking.getId()), null, null));
    }

    @Test
    @DisplayName("guest bundles require every booking to belong to the same guest email")
    void guestEmailMismatchIsNotFound() {
        stubIdentitySaves();
        Booking booking = booking("LB-AAA", "EUR", new BigDecimal("60.00"));
        booking.setLoggedInUser(null);
        booking.setBookingSource(BookingSource.GUEST_USER);
        booking.setGuestEmail("real@guest.com");

        assertThrows(ResourceNotFoundException.class, () -> service.prepareGroupForCheckout(
                null, "other@guest.com", List.of(booking.getId()), null, null));
    }
}
