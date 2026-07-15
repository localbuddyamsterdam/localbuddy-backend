package com.localbuddy.auth;

/**
 * Result of an email-existence probe used by the email-first sign-in flow.
 *
 * <p>{@code registered} tells the client whether to show a password step (a
 * returning user) or a sign-up step (a first-time user). {@code hasPassword} is
 * {@code false} for accounts that only ever signed in with a social provider, so
 * the client can steer them back to Google/Facebook instead of a dead-end
 * password prompt.
 *
 * <p>Existence is already discoverable through signup ("email already exists"),
 * so this endpoint doesn't widen the enumeration surface; it is rate-limited like
 * the other public auth endpoints.
 */
public record CheckEmailResponse(
        boolean registered,
        boolean hasPassword
) {
}
