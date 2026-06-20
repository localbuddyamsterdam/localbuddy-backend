package com.localbuddy.gdpr;

import com.localbuddy.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read-only view over payments for GDPR export. */
public interface GdprPaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByBooking_TravelerUser_IdOrderByCreatedAtDesc(UUID travelerUserId);
}
