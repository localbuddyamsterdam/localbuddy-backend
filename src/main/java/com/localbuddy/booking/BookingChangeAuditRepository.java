package com.localbuddy.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingChangeAuditRepository extends JpaRepository<BookingChangeAudit, UUID> {

    List<BookingChangeAudit> findByBookingIdOrderByChangedAtDesc(UUID bookingId);
}
