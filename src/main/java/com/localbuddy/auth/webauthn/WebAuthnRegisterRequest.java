package com.localbuddy.auth.webauthn;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Completes passkey enrollment. The client extracts the ready-to-verify pieces from the browser's
 * {@code AuthenticatorAttestationResponse} — {@code getPublicKey()} (SPKI DER),
 * {@code getPublicKeyAlgorithm()} and {@code getAuthenticatorData()} — so the server verifies with
 * plain JDK crypto and needs no CBOR/attestation parsing. Enrollment happens on an authenticated
 * session, so trusting the browser-extracted key is equivalent to attestation "none" (the norm).
 */
public record WebAuthnRegisterRequest(
        @NotBlank String challengeId,
        /** Credential id, base64url. */
        @NotBlank String credentialId,
        /** SubjectPublicKeyInfo DER, base64. */
        @NotBlank String publicKey,
        /** COSE algorithm: -7 ES256, -257 RS256, -8 Ed25519. */
        int algorithm,
        /** Raw clientDataJSON, base64url. */
        @NotBlank String clientDataJson,
        /** Raw authenticator data, base64url. */
        @NotBlank String authenticatorData,
        List<String> transports,
        /** Optional user-facing name for the device ("iPhone", "This Mac"). */
        String label
) {
}
