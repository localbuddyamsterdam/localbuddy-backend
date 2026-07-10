package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingExpiryService;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.booking.CancellationRefundPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the post-payment hardening at the service level, without Spring or a database: once the
 * charge has landed, a failure in any best-effort finalisation step (invoice, ledger, notification,
 * …) must not propagate out of the flow. The payment stays PAID, the booking stays CONFIRMED, the
 * caller still gets a success response, and the remaining best-effort steps still run.
 *
 * <p>With no active transaction on the thread, {@code PaymentService.afterCommit} runs the
 * best-effort steps inline, so this test drives exactly the orchestration + try/catch logic. The
 * real {@code REQUIRES_NEW}/after-commit transaction isolation is exercised end-to-end against a
 * database by {@code PaidBookingFinalizationIT}.
 */
class PaymentServiceFinalizationTest {

    private PaymentRepository paymentRepository;
    private BookingRepository bookingRepository;
    private PaidBookingFinalizer finalizer;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        bookingRepository = mock(BookingRepository.class);
        finalizer = mock(PaidBookingFinalizer.class);

        paymentService = new PaymentService(
                paymentRepository,
                bookingRepository,
                new BigDecimal("20"),
                mock(PaymentCheckoutProvider.class),
                mock(PaymentWebhookEventRepository.class),
                mock(CancellationRefundPolicyService.class),
                mock(PaymentTransactionService.class),
                mock(BookingExpiryService.class),
                mock(com.localbuddy.pricing.PricingEngine.class),
                mock(com.localbuddy.payout.HostLedgerService.class),
                mock(com.localbuddy.giftcard.GiftCardService.class),
                finalizer,
                new BigDecimal("0.50"));

        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("invoice generation throwing does not roll back or 500 a paid, confirmed booking")
    void invoiceFailureDoesNotFailThePaidConfirmedBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Booking booking = booking(bookingId, BookingStatus.PENDING_PAYMENT);
        Payment payment = payment(paymentId, booking);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        // The invoice step blows up, as if invoice generation threw after the charge succeeded.
        doThrow(new RuntimeException("invoice generation boom")).when(finalizer).generateInvoice(paymentId);

        // No exception escapes: if completeWithoutStripeCharge threw, this line would fail the test.
        PaymentCheckoutResponse response = paymentService.completeWithoutStripeCharge(paymentId);

        // The customer sees success and the core state is committed.
        assertEquals(PaymentStatus.PAID, response.paymentStatus(), "caller gets a paid response");
        assertEquals(PaymentStatus.PAID, payment.getPaymentStatus(), "payment stays PAID");
        assertNotNull(payment.getPaidAt(), "paidAt is set");
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus(), "booking stays CONFIRMED");

        // The failing step was attempted, and every other best-effort step still ran.
        verify(finalizer).generateInvoice(paymentId);
        verify(finalizer).recordHostEarning(paymentId);
        verify(finalizer).recordGiftCardRedemption(paymentId);
        verify(finalizer).redeemPromoCode(bookingId);
        verify(finalizer).redeemReferralCode(bookingId);
        verify(finalizer).sendConfirmationNotification(bookingId);
    }

    @Test
    @DisplayName("a failure in an early best-effort step does not block the later steps")
    void oneFailingStepDoesNotBlockTheOthers() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Booking booking = booking(bookingId, BookingStatus.ACCEPTED);
        Payment payment = payment(paymentId, booking);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        // The very first best-effort step fails.
        doThrow(new IllegalStateException("promo redemption boom")).when(finalizer).redeemPromoCode(bookingId);

        PaymentCheckoutResponse response = paymentService.completeWithoutStripeCharge(paymentId);

        assertEquals(PaymentStatus.PAID, response.paymentStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());

        // Steps after the failing one still ran — proving per-step isolation, not a single try/catch
        // around the whole block that would abort at the first failure.
        verify(finalizer).redeemReferralCode(bookingId);
        verify(finalizer).recordHostEarning(paymentId);
        verify(finalizer).recordGiftCardRedemption(paymentId);
        verify(finalizer).generateInvoice(paymentId);
        verify(finalizer).sendConfirmationNotification(bookingId);
    }

    private Booking booking(UUID id, BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setStatus(status);
        return booking;
    }

    private Payment payment(UUID id, Booking booking) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("EUR");
        return payment;
    }
}
