package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Read-only view over bookings for trip-safety ownership checks. */
public interface TripSafetyBookingRepository extends JpaRepository<Booking, UUID> {

    /** Resolve a guest booking by its public reference (for the no-login SOS endpoint). */
    Optional<Booking> findByBookingReference(String bookingReference);
}
