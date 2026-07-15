package com.localbuddy.auth.webauthn;

import java.time.Instant;
import java.util.UUID;

/** A registered passkey as shown in account settings. */
public record WebAuthnCredentialResponse(
        UUID id,
        String label,
        Instant createdAt,
        Instant lastUsedAt
) {
}
