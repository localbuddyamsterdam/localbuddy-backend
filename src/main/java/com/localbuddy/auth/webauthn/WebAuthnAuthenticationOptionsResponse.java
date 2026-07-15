package com.localbuddy.auth.webauthn;

import java.util.List;

/** Challenge + relying-party id for {@code navigator.credentials.get()}. */
public record WebAuthnAuthenticationOptionsResponse(
        String challengeId,
        String challenge,
        String rpId,
        /** Credential ids (base64url) for the given email — empty for a userless ceremony. */
        List<String> allowCredentialIds
) {
}
