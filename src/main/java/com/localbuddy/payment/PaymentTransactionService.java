package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final com.localbuddy.pricing.PricingEngine pricingEngine;

    public PaymentTransactionService(
            PaymentRepository paymentRepository,
            BookingRepository bookingRepository,
            com.localbuddy.pricing.PricingEngine pricingEngine
    ) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.pricingEngine = pricingEngine;
    }

    @Transactional
    public Payment preparePaymentForCheckout(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validatePaymentRequestUser(userId, booking);
        validateBookingReadyForCheckout(booking);

        Payment payment = getOrCreatePendingPaymentForBooking(booking);

        // Initialize lazy values needed later outside the transaction
        payment.getId();
        payment.getAmount();
        payment.getCurrency();

        Booking paymentBooking = payment.getBooking();
        paymentBooking.getId();
        paymentBooking.getBookingReference();

        return payment;
    }

    @Transactional
    public Payment attachCheckoutResult(UUID paymentId, PaymentCheckoutResult checkoutResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BadRequestException("Payment is already completed for this booking");
        }

        payment.setPaymentStatus(PaymentStatus.PROCESSING);
        payment.setCheckoutUrl(checkoutResult.checkoutUrl());
        payment.setProviderCheckoutSessionId(checkoutResult.providerCheckoutSessionId());
        payment.setProviderPaymentIntentId(checkoutResult.providerPaymentIntentId());

        if (checkoutResult.paymentMethodType() != null) {
            payment.setPaymentMethodType(checkoutResult.paymentMethodType());
        }

        return paymentRepository.save(payment);
    }

    private Payment getOrCreatePendingPaymentForBooking(Booking booking) {
        paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.PAID)
        ).ifPresent(existingPayment -> {
            if (existingPayment.getPaymentStatus() == PaymentStatus.PAID) {
                throw new BadRequestException("Payment is already completed for this booking");
            }
        });

        return paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING)
        ).orElseGet(() -> createPaymentEntityForBooking(booking));
    }

    private Payment createPaymentEntityForBooking(Booking booking) {
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        pricingEngine.applyTo(payment, booking);

        return paymentRepository.save(payment);
    }

    private void validatePaymentRequestUser(UUID userId, Booking booking) {
        if (booking.getLoggedInUser() != null &&
                booking.getLoggedInUser().getId().equals(userId)) {
            return;
        }

        throw new ResourceNotFoundException("Booking not found");
    }

    private void validateBookingReadyForCheckout(Booking booking) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.ACCEPTED) {
            throw new BadRequestException("Checkout can be created only for bookings pending payment");
        }
    }
}