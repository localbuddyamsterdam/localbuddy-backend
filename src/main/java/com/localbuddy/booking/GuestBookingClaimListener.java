package com.localbuddy.booking;

import com.localbuddy.auth.UserEmailConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Attaches a traveller's past guest bookings to their account once their email ownership is confirmed.
 *
 * <p>Runs asynchronously <em>after</em> the auth transaction commits. Waiting for commit guarantees a
 * just-created account row is visible for the {@code traveler_user_id} FK and keeps
 * login/verification latency untouched. Any failure here is logged and swallowed — claiming past
 * guest bookings is a convenience and must never break sign-in or email verification.
 */
@Component
public class GuestBookingClaimListener {

    private static final Logger log = LoggerFactory.getLogger(GuestBookingClaimListener.class);

    private final GuestBookingClaimService guestBookingClaimService;

    public GuestBookingClaimListener(GuestBookingClaimService guestBookingClaimService) {
        this.guestBookingClaimService = guestBookingClaimService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserEmailConfirmed(UserEmailConfirmedEvent event) {
        try {
            guestBookingClaimService.claimGuestBookings(event.userId(), event.email());
        } catch (RuntimeException ex) {
            log.warn("GUEST_CLAIM failed userId={} reason={}", event.userId(), ex.toString());
        }
    }
}
