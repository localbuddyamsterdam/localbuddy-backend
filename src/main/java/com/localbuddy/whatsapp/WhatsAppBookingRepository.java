package com.localbuddy.whatsapp;

import com.localbuddy.booking.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only view over bookings for WhatsApp contact-link generation. */
public interface WhatsAppBookingRepository extends JpaRepository<Booking, UUID> {
}
