package com.localbuddy.gdpr;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read-only view over bookings for GDPR export; leaves the booking module untouched. */
public interface GdprBookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByTravelerUserIdOrderByCreatedAtDesc(UUID travelerUserId);
}
