package com.localbuddy.auth.webauthn;

/**
 * Asks for a sign-in challenge. {@code email} is optional: when present, the response lists that
 * account's credential ids (allowCredentials); when absent, the client runs a userless
 * (discoverable-credential) ceremony and the passkey itself identifies the user via userHandle.
 */
public record WebAuthnAuthenticationOptionsRequest(
        String email
) {
}
