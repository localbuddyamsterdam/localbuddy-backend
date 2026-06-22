package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.payment.Payment;
import com.localbuddy.payment.PaymentCheckoutProvider;
import com.localbuddy.payment.PaymentRepository;
import com.localbuddy.payment.PaymentStatus;
import com.localbuddy.waitlist.WaitlistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final long pendingPaymentExpirationMinutes;

    public BookingExpiryService(
            BookingRepository bookingRepository,
            AvailabilitySlotRepository availabilitySlotRepository,
            PaymentRepository paymentRepository,
            WaitlistService waitlistService,
            PaymentCheckoutProvider paymentCheckoutProvider,
            BookingConfirmationNotifier bookingConfirmationNotifier,
            @Value("${app.booking.pending-payment-expiration-minutes:15}") long pendingPaymentExpirationMinutes
    ) {
        this.bookingRepository = bookingRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.paymentRepository = paymentRepository;
        this.waitlistService = waitlistService;
        this.paymentCheckoutProvider = paymentCheckoutProvider;
        this.bookingConfirmationNotifier = bookingConfirmationNotifier;
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

        cancelOpenPaymentsForExpiredBooking(booking);
        releaseAvailabilityCapacity(booking);

        booking.setStatus(BookingStatus.EXPIRED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason("Booking expired because payment was not completed in time");

        bookingRepository.save(booking);
    }

    private void cancelOpenPaymentsForExpiredBooking(Booking booking) {
        List<Payment> openPayments = paymentRepository.findByBookingIdAndPaymentStatusIn(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING)
        );

        for (Payment payment : openPayments) {
            // Proactively expire the Stripe session so a late payment can't sneak
            // in after we release the seat.
            paymentCheckoutProvider.expireCheckout(payment.getProviderCheckoutSessionId());
            payment.setPaymentStatus(PaymentStatus.CANCELLED);
            payment.setCancelledAt(Instant.now());
        }

        paymentRepository.saveAll(openPayments);
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