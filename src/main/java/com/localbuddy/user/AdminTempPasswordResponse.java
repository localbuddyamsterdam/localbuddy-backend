package com.localbuddy.user;

import java.util.UUID;

/**
 * Returned once when an admin resets a user's password. Carries the generated
 * temporary password in plaintext — shown to the admin a single time to relay to
 * the user; the user's sessions are revoked and they are forced to change it on
 * next login.
 */
public record AdminTempPasswordResponse(
        UUID userId,
        String email,
        String temporaryPassword
) {
}
