package com.localbuddy.whatsapp;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Generates a click-to-chat link to message the other party on a booking. */
@Service
public class WhatsAppBookingService {

    private final WhatsAppBookingRepository bookingRepository;
    private final WhatsAppService whatsAppService;

    public WhatsAppBookingService(WhatsAppBookingRepository bookingRepository,
                                  WhatsAppService whatsAppService) {
        this.bookingRepository = bookingRepository;
        this.whatsAppService = whatsAppService;
    }

    @Transactional(readOnly = true)
    public BookingContactLinkResponse contactLink(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        boolean isTraveler = booking.getLoggedInUser() != null
                && booking.getLoggedInUser().getId().equals(userId);
        boolean isHost = booking.getLocalProfile() != null
                && booking.getLocalProfile().getUser() != null
                && booking.getLocalProfile().getUser().getId().equals(userId);

        if (!isTraveler && !isHost) {
            throw new ResourceNotFoundException("Booking not found");
        }

        String message = "Hi, regarding LocalBuddy booking " + booking.getBookingReference();

        String counterpartPhone;
        String role;
        if (isTraveler) {
            role = "HOST";
            counterpartPhone = booking.getLocalProfile() != null && booking.getLocalProfile().getUser() != null
                    ? booking.getLocalProfile().getUser().getPhone() : null;
        } else {
            role = "TRAVELER";
            counterpartPhone = booking.getLoggedInUser() != null
                    ? booking.getLoggedInUser().getPhone()
                    : booking.getGuestPhone();
        }

        if (counterpartPhone == null || counterpartPhone.isBlank()) {
            throw new BadRequestException("The other party has no phone number on file");
        }

        String link = whatsAppService.buildClickToChatLink(counterpartPhone, message);
        return new BookingContactLinkResponse(role, link);
    }
}
