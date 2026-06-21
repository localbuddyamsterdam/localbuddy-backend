package com.localbuddy.calendar;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only view over bookings for calendar export. */
public interface CalendarBookingRepository extends JpaRepository<Booking, UUID> {
}
