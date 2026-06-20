package com.localbuddy.giftcard;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only view over bookings for linking a redemption to a booking. */
public interface GiftCardBookingRepository extends JpaRepository<Booking, UUID> {
}
