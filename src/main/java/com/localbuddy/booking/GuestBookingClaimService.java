package com.localbuddy.booking;

import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Attaches past guest bookings to the traveller account that owns their email address.
 *
 * <p>A guest booking records only a {@code guestEmail} and no {@code loggedInUser}. Once the person
 * proves ownership of that email by creating and verifying an account (or signing in with a
 * verified/social account — see {@link com.localbuddy.auth.UserEmailConfirmedEvent}), their guest
 * bookings should appear and be manageable under that login.
 *
 * <p>Claiming sets the booking's {@code loggedInUser} FK while <em>leaving</em>
 * {@code bookingSource = GUEST_USER} and the guest_* fields intact. That is deliberate: it keeps the
 * emailed guest self-service links working (pay, check-in, no-show, reference+email lookup) — those
 * flows gate on {@code bookingSource == GUEST_USER} — while {@link BookingService#getMyBookings} now
 * also returns the booking and the traveller can cancel/manage it as their own. The DB's
 * {@code chk_bookings_traveler_or_guest} constraint is an OR, so a row may legitimately carry both a
 * traveller and guest details.
 */
@Service
public class GuestBookingClaimService {

    private static final Logger log = LoggerFactory.getLogger(GuestBookingClaimService.class);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public GuestBookingClaimService(BookingRepository bookingRepository, UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    /**
     * Attaches every unclaimed guest booking made with {@code email} to the given traveller, except
     * any active guest booking whose slot the traveller already holds an active booking for. That
     * lone exception avoids a violation of the {@code ux_bookings_active_traveler_slot} partial-unique
     * index (one active booking per traveller per slot); the skipped guest booking is a duplicate the
     * account already covers and stays reachable through the reference+email guest lookup.
     *
     * <p>Non-active guest bookings (cancelled/expired/completed/declined) are always attached — they
     * are outside that unique index — so the account shows the traveller's full guest history.
     *
     * @return the number of guest bookings newly attached to the traveller
     */
    @Transactional
    public int claimGuestBookings(UUID userId, String email) {
        if (userId == null || email == null) {
            return 0;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            return 0;
        }

        List<Booking> candidates =
                bookingRepository.findByLoggedInUserIsNullAndGuestEmail(normalizedEmail);
        if (candidates.isEmpty()) {
            return 0;
        }

        // Slots on which the traveller already holds an active booking under their account: attaching
        // a second active booking for the same slot would break ux_bookings_active_traveler_slot.
        // (The guest partial-unique guarantees at most one active *unclaimed* guest booking per slot,
        // so among the candidates themselves there is never an intra-set active-slot collision.)
        Set<UUID> travellerActiveSlotIds = new HashSet<>(
                bookingRepository.findActiveSlotIdsForUser(userId, BookingStatus.ACTIVE));

        List<UUID> claimableIds = new ArrayList<>(candidates.size());
        int skippedConflicts = 0;
        for (Booking booking : candidates) {
            UUID slotId = booking.getAvailabilitySlot().getId();
            if (BookingStatus.ACTIVE.contains(booking.getStatus()) && travellerActiveSlotIds.contains(slotId)) {
                skippedConflicts++;
                continue;
            }
            claimableIds.add(booking.getId());
        }

        if (claimableIds.isEmpty()) {
            log.info("GUEST_CLAIM userId={} candidates={} claimed=0 skippedConflicts={}",
                    userId, candidates.size(), skippedConflicts);
            return 0;
        }

        User travellerRef = userRepository.getReferenceById(userId);
        int claimed = bookingRepository.attachBookingsToUser(travellerRef, claimableIds, Instant.now());
        log.info("GUEST_CLAIM userId={} candidates={} claimed={} skippedConflicts={}",
                userId, candidates.size(), claimed, skippedConflicts);
        return claimed;
    }
}
