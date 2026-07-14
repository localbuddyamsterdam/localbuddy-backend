package com.localbuddy.user;

import java.util.UUID;

/**
 * Returned once when an admin creates a user. Carries the generated temporary
 * password in plaintext — shown to the admin a single time to relay to the new
 * user; it is never persisted in plaintext or returned again. The user is forced
 * to change it on first login.
 */
public record AdminUserCreatedResponse(
        UUID id,
        String firstName,
        String lastName,
        String preferredName,
        String email,
        UserRole role,
        UserStatus status,
        String temporaryPassword
) {
}
