package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.giftcard.GiftCardService;
import com.localbuddy.payment.Payment;
import com.localbuddy.payment.PaymentCheckoutProvider;
import com.localbuddy.payment.PaymentGroup;
import com.localbuddy.payment.PaymentGroupRepository;
import com.localbuddy.payment.PaymentGroupStatus;
import com.localbuddy.payment.PaymentRepository;
import com.localbuddy.payment.PaymentStatus;
import com.localbuddy.waitlist.WaitlistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BookingExpiryService {

    private final BookingRepository bookingRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final PaymentRepository paymentRepository;
    private final WaitlistService waitlistService;
    private final PaymentCheckoutProvider paymentCheckoutProvider;
    private final BookingConfirmationNotifier bookingConfirmationNotifier;
    private final GiftCardService giftCardService;
    private final PaymentGroupRepository paymentGroupRepository;
    private final long pendingPaymentExpirationMinutes;

    public BookingExpiryService(
            BookingRepository bookingRepository,
            AvailabilitySlotRepository availabilitySlotRepository,
            PaymentRepository paymentRepository,
            WaitlistService waitlistService,
            PaymentCheckoutProvider paymentCheckoutProvider,
            BookingConfirmationNotifier bookingConfirmationNotifier,
            GiftCardService giftCardService,
            PaymentGroupRepository paymentGroupRepository,
            @Value("${app.booking.pending-payment-expiration-minutes:15}") long pendingPaymentExpirationMinutes
    ) {
        this.bookingRepository = bookingRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.paymentRepository = paymentRepository;
        this.waitlistService = waitlistService;
        this.paymentCheckoutProvider = paymentCheckoutProvider;
        this.bookingConfirmationNotifier = bookingConfirmationNotifier;
        this.giftCardService = giftCardService;
        this.paymentGroupRepository = paymentGroupRepository;
        this.pendingPaymentExpirationMinutes = pendingPaymentExpirationMinutes;
    }

    /**
     * Immediately releases a booking's slot when its payment fails or its Stripe
     * checkout session expires, instead of waiting for the periodic expiry sweep.
     */
    @Transactional
    public void releaseBookingSlotAfterFailedPayment(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);

        if (booking == null || booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            return;
        }

        releaseAvailabilityCapacity(booking);

        booking.setStatus(BookingStatus.EXPIRED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason("Booking released because the payment failed or the checkout expired");

        bookingRepository.save(booking);
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-processor-delay-ms:60000}")
    @Transactional
    public void expirePendingPaymentBookings() {
        Instant cutoff = Instant.now().minusSeconds(pendingPaymentExpirationMinutes * 60);

        List<Booking> expiredBookings =
                bookingRepository.findTop100ByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(
                        BookingStatus.PENDING_PAYMENT,
                        cutoff
                );

        for (Booking booking : expiredBookings) {
            expireBookingIfStillUnpaid(booking);
        }
    }

    private void expireBookingIfStillUnpaid(Booking booking) {
        boolean hasPaidPayment = paymentRepository
                .findByBookingIdAndPaymentStatusIn(
                        booking.getId(),
                        List.of(PaymentStatus.PAID)
                )
                .stream()
                .findAny()
                .isPresent();

        if (hasPaidPayment) {
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
            bookingConfirmationNotifier.sendConfirmation(booking);
            return;
        }

        if (!cancelOpenPaymentsForExpiredBooking(booking)) {
            // A bundle this booking belongs to was paid concurrently (payment landed right at
            // the deadline): the webhook confirms the booking — do not release the seat.
            return;
        }
        releaseAvailabilityCapacity(booking);

        booking.setStatus(BookingStatus.EXPIRED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason("Booking expired because payment was not completed in time");

        bookingRepository.save(booking);
    }

    /**
     * Cancels the booking's open payments (and, for bundle members, the whole bundle).
     *
     * @return false when the booking must NOT be expired after all — its bundle was paid
     * concurrently and the paid-webhook is confirming it.
     */
    private boolean cancelOpenPaymentsForExpiredBooking(Booking booking) {
        List<Payment> openPayments = paymentRepository.findByBookingIdAndPaymentStatusIn(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING)
        );

        List<Payment> cancelled = new java.util.ArrayList<>();
        boolean proceedWithExpiry = true;

        for (Payment payment : openPayments) {
            if (payment.getPaymentGroup() != null) {
                // Bundle member: the Stripe session and the gift-card draw live on the GROUP.
                // Cancel the whole group once (idempotent); sibling bookings share the same
                // requestedAt, so this same sweep pass expires each of them right after.
                if (!cancelGroupForExpiredBundle(payment.getPaymentGroup())) {
                    // The bundle just got PAID under our feet. The webhook (which held the
                    // group lock) has already marked this member PAID and confirmed the
                    // booking — our in-memory copy is stale, so leave it untouched.
                    proceedWithExpiry = false;
                    continue;
                }
            } else {
                // Proactively expire the Stripe session so a late payment can't sneak
                // in after we release the seat.
                paymentCheckoutProvider.expireCheckout(payment.getProviderCheckoutSessionId());
                // Return any reserved gift-card share so the balance isn't stranded on the
                // cancelled payment (the expiry webhook only releases PENDING/PROCESSING ones,
                // and by then this payment is already CANCELLED).
                releaseGiftCardIfAny(payment);
            }
            payment.setPaymentStatus(PaymentStatus.CANCELLED);
            payment.setCancelledAt(Instant.now());
            cancelled.add(payment);
        }

        paymentRepository.saveAll(cancelled);
        return proceedWithExpiry;
    }

    /**
     * Cancels a bundle checkout when one of its bookings expires unpaid: expires the shared
     * Stripe session (so no late payment can land) and returns the group's single gift-card
     * draw. Idempotent — only the first expiring member performs the transition.
     *
     * @return true when the group is (now) dead and the member may be cancelled; false when
     * the group was PAID concurrently and the member/booking must be left alone.
     */
    private boolean cancelGroupForExpiredBundle(PaymentGroup memberGroup) {
        // Lock the group row and re-read: the paid-webhook can be marking this exact group
        // PAID concurrently (payment completed right at the deadline). The lock serializes
        // us behind it, and the status re-check below then routes us correctly.
        PaymentGroup group = paymentGroupRepository.findByIdForUpdate(memberGroup.getId())
                .orElse(null);
        if (group == null) {
            return true;
        }

        if (group.getStatus() == PaymentGroupStatus.PAID) {
            return false;
        }

        if (group.getStatus() != PaymentGroupStatus.PENDING
                && group.getStatus() != PaymentGroupStatus.PROCESSING) {
            // Already cancelled/failed (e.g. by a sibling's sweep iteration) — nothing to do,
            // but the member itself must still be cancelled.
            return true;
        }

        paymentCheckoutProvider.expireCheckout(group.getProviderCheckoutSessionId());

        group.setStatus(PaymentGroupStatus.CANCELLED);
        group.setCancelledAt(Instant.now());
        group.setFailureReason("Bundle expired because payment was not completed in time");

        if (group.getGiftCardId() != null
                && group.getGiftCardAmount() != null
                && group.getGiftCardAmount().signum() > 0
                && group.getGiftCardReturnedAt() == null) {
            giftCardService.returnToCard(group.getGiftCardId(), group.getGiftCardAmount());
            group.setGiftCardReturnedAt(Instant.now());
        }

        paymentGroupRepository.save(group);
        return true;
    }

    private void releaseGiftCardIfAny(Payment payment) {
        if (payment.getGiftCardId() != null
                && payment.getGiftCardAmount() != null
                && payment.getGiftCardAmount().signum() > 0) {
            giftCardService.returnToCard(payment.getGiftCardId(), payment.getGiftCardAmount());
            payment.setGiftCardId(null);
            payment.setGiftCardAmount(BigDecimal.ZERO);
        }
    }

    private void releaseAvailabilityCapacity(Booking booking) {
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(
                booking.getAvailabilitySlot().getId()
        ).orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        int seatsConsumed = booking.getSeatsBlocked() != null
                ? booking.getSeatsBlocked()
                : booking.getGuestsCount();
        int updatedBookedCount = Math.max(0, slot.getBookedCount() - seatsConsumed);
        slot.setBookedCount(updatedBookedCount);

        if (slot.getStatus() == AvailabilityStatus.BLOCKED &&
                updatedBookedCount < slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.AVAILABLE);
        }

        availabilitySlotRepository.save(slot);
        waitlistService.notifyOpenedSpots(slot);
    }
}