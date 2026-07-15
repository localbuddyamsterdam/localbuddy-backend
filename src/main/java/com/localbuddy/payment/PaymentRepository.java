package com.localbuddy.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
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

    /** Payments in a given status whose paidAt falls in [from, to] — admin dashboard money/series windows. */
    List<Payment> findByPaymentStatusAndPaidAtBetween(
            PaymentStatus paymentStatus,
            Instant from,
            Instant to
    );

    /** Count of payments across several statuses — admin dashboard queues (e.g. FAILED + REFUND_PENDING). */
    long countByPaymentStatusIn(Collection<PaymentStatus> statuses);

    /** Member payments of a bundle checkout, in creation order. */
    List<Payment> findByPaymentGroupIdOrderByCreatedAtAsc(UUID paymentGroupId);
}