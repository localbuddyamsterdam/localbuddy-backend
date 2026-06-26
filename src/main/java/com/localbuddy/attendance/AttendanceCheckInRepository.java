package com.localbuddy.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceCheckInRepository extends JpaRepository<AttendanceCheckIn, UUID> {

    /** The host's check-in for a slot (slot-level, booking null). */
    Optional<AttendanceCheckIn> findByAvailabilitySlotIdAndRole(UUID availabilitySlotId, CheckInRole role);

    /** A guest's check-in for their booking. */
    Optional<AttendanceCheckIn> findByBookingIdAndRole(UUID bookingId, CheckInRole role);

    /** All guest check-ins for a slot's bookings — for the host attendance view. */
    List<AttendanceCheckIn> findByAvailabilitySlotIdAndRoleAndBookingIdIsNotNull(
            UUID availabilitySlotId, CheckInRole role);

    /** GDPR export. */
    List<AttendanceCheckIn> findByUserId(UUID userId);

    /** Retention cleanup. */
    @Modifying
    @Query("delete from AttendanceCheckIn c where c.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
