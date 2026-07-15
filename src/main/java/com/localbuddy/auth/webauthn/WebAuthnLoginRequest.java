package com.localbuddy.auth.webauthn;

import jakarta.validation.constraints.NotBlank;

/** The browser's {@code AuthenticatorAssertionResponse}, base64url-encoded piecewise. */
public record WebAuthnLoginRequest(
        @NotBlank String challengeId,
        @NotBlank String credentialId,
        @NotBlank String clientDataJson,
        @NotBlank String authenticatorData,
        @NotBlank String signature,
        /** Present for discoverable credentials — identifies the account. */
        String userHandle
) {
}
