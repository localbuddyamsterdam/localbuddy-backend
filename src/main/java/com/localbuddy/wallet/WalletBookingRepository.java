package com.localbuddy.wallet;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only view over bookings for wallet pass generation. */
public interface WalletBookingRepository extends JpaRepository<Booking, UUID> {
}
