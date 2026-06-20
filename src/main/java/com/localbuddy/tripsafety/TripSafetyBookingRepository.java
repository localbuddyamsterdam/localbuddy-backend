package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only view over bookings for trip-safety ownership checks. */
public interface TripSafetyBookingRepository extends JpaRepository<Booking, UUID> {
}
