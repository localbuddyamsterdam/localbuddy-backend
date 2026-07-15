package com.localbuddy.auth.webauthn;

import java.util.List;

/**
 * Everything the browser needs to run {@code navigator.credentials.create()}: a fresh challenge,
 * the relying-party identity, the user entity, and the already-registered credential ids to
 * exclude (so a device doesn't enroll the same passkey twice). The client assembles the actual
 * {@code PublicKeyCredentialCreationOptions} from these fields.
 */
public record WebAuthnRegistrationOptionsResponse(
        String challengeId,
        String challenge,
        String rpId,
        String rpName,
        /** WebAuthn user handle (base64url) — echoed back as userHandle at sign-in. */
        String userHandle,
        String userName,
        String userDisplayName,
        List<String> excludeCredentialIds
) {
}
