package com.localbuddy.notification;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view over {@link Booking} used by the reminder scheduler. Kept separate
 * from the booking module's own repository so the booking core stays untouched.
 */
public interface BookingReminderRepository extends JpaRepository<Booking, UUID> {

    @Query("""
            select b from Booking b
            where b.status = com.localbuddy.booking.BookingStatus.CONFIRMED
              and b.availabilitySlot.startTime >= :from
              and b.availabilitySlot.startTime < :to
            """)
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "availabilitySlot", "experience", "loggedInUser"})
    List<Booking> findConfirmedStartingBetween(@Param("from") Instant from, @Param("to") Instant to);
}
