package com.localbuddy.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

    Optional<Payment> findByProviderAndProviderCheckoutSessionId(
            PaymentProvider provider,
            String providerCheckoutSessionId
    );

    Optional<Payment> findByProviderAndProviderPaymentIntentId(
            PaymentProvider provider,
            String providerPaymentIntentId
    );

    boolean existsByBookingIdAndPaymentStatusIn(
            UUID bookingId,
            List<PaymentStatus> statuses
    );

    List<Payment> findByPaymentStatusOrderByCreatedAtDesc(PaymentStatus paymentStatus);

    List<Payment> findAllByOrderByCreatedAtDesc();

    Optional<Payment> findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
            UUID bookingId,
            List<PaymentStatus> statuses
    );

    List<Payment> findByBookingIdAndPaymentStatusIn(
            UUID bookingId,
            List<PaymentStatus> statuses
    );

    /** Host earnings: payments for a host's bookings in a given status (read-only). */
    List<Payment> findByBooking_LocalProfile_IdAndPaymentStatus(
            UUID localProfileId,
            PaymentStatus paymentStatus
    );
}