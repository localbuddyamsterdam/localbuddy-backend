package com.localbuddy.auth;

import java.util.UUID;

/**
 * Published when a traveller proves ownership of their account email in an authenticated context:
 * completing email verification, signing in as an already-verified user, or a social login (the
 * provider asserts the email). At that point email-scoped guest data — notably guest bookings made
 * with the same address — may safely be attached to the account.
 *
 * <p>Publishers gate this to {@link com.localbuddy.user.UserRole#LOGGED_IN_USER} accounts: only a
 * traveller login surfaces guest bookings (a host/admin "my bookings" view is scoped differently),
 * and gating on a proven-verified email prevents someone who merely signed up with a stranger's
 * address from claiming that stranger's guest bookings before verifying.
 *
 * <p>Carries only the id and normalized email so listeners can run in their own transaction after
 * commit without touching the auth aggregate.
 */
public record UserEmailConfirmedEvent(UUID userId, String email) {
}
