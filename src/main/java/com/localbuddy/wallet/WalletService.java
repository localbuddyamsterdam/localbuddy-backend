package com.localbuddy.wallet;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    /** A signed pass plus its human-friendly download name (from the booking reference). */
    public record PassFile(String filename, byte[] bytes) {
    }

    public PassFile applePass(UUID userId, UUID bookingId) {
        Booking booking = requireParticipant(userId, bookingId);
        String reference = booking.getBookingReference() == null ? "booking"
                : booking.getBookingReference().replaceAll("[^A-Za-z0-9._-]", "");
        return new PassFile("localbuddy-" + reference + ".pkpass",
                appleWalletService.buildPkpass(buildData(booking)));
    }

    private WalletPassData buildData(Booking booking) {
        return WalletPassData.from(booking);
    }

    private Booking requireParticipant(UUID userId, UUID bookingId) {
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
        if (booking.getBookingReference() == null) {
            throw new BadRequestException("Booking is not ready for a wallet pass");
        }
        return booking;
    }
}
