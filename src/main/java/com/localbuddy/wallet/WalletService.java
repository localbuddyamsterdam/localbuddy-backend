package com.localbuddy.wallet;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Resolves a booking into wallet-pass data and delegates to the Apple/Google providers. */
@Service
public class WalletService {

    private final WalletBookingRepository bookingRepository;
    private final AppleWalletService appleWalletService;
    private final GoogleWalletService googleWalletService;

    public WalletService(WalletBookingRepository bookingRepository,
                         AppleWalletService appleWalletService,
                         GoogleWalletService googleWalletService) {
        this.bookingRepository = bookingRepository;
        this.appleWalletService = appleWalletService;
        this.googleWalletService = googleWalletService;
    }

    @Transactional(readOnly = true)
    public WalletLinksResponse links(UUID userId, UUID bookingId) {
        WalletPassData data = buildData(requireParticipant(userId, bookingId));
        String googleUrl = googleWalletService.isConfigured() ? googleWalletService.buildSaveUrl(data) : null;
        return new WalletLinksResponse(
                googleWalletService.isConfigured(),
                googleUrl,
                appleWalletService.isConfigured(),
                "/api/bookings/" + bookingId + "/wallet/apple.pkpass"
        );
    }

    @Transactional(readOnly = true)
    public String googleSaveUrl(UUID userId, UUID bookingId) {
        return googleWalletService.buildSaveUrl(buildData(requireParticipant(userId, bookingId)));
    }

    @Transactional(readOnly = true)
    public byte[] applePass(UUID userId, UUID bookingId) {
        return appleWalletService.buildPkpass(buildData(requireParticipant(userId, bookingId)));
    }

    private WalletPassData buildData(Booking booking) {
        Experience experience = booking.getExperience();
        String title = experience != null && experience.getTitle() != null
                ? experience.getTitle() : "LocalBuddy experience";

        String hostName = null;
        if (booking.getLocalProfile() != null && booking.getLocalProfile().getUser() != null) {
            hostName = booking.getLocalProfile().getUser().getFullName();
        }

        Instant start = booking.getAvailabilitySlot() != null
                ? booking.getAvailabilitySlot().getStartTime() : null;
        Instant end = booking.getAvailabilitySlot() != null
                ? booking.getAvailabilitySlot().getEndTime() : null;

        String location = null;
        if (experience != null) {
            if (experience.getMeetingArea() != null && !experience.getMeetingArea().isBlank()) {
                location = experience.getMeetingArea();
            } else if (experience.getCity() != null) {
                location = experience.getCity().getName();
            }
        }

        int guests = booking.getGuestsCount() != null ? booking.getGuestsCount() : 1;

        return new WalletPassData(booking.getBookingReference(), title, hostName, start, end, location, guests);
    }

    private Booking requireParticipant(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        boolean isTraveler = booking.getTravelerUser() != null
                && booking.getTravelerUser().getId().equals(userId);
        boolean isHost = booking.getLocalProfile() != null
                && booking.getLocalProfile().getUser() != null
                && booking.getLocalProfile().getUser().getId().equals(userId);
        if (!isTraveler && !isHost) {
            throw new ResourceNotFoundException("Booking not found");
        }
        if (booking.getBookingReference() == null) {
            throw new BadRequestException("Booking is not ready for a wallet pass");
        }
        return booking;
    }
}
