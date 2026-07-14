package com.localbuddy.user;

import com.localbuddy.auth.StrongPassword;
import jakarta.validation.constraints.NotBlank;

/**
 * Body for the forced first-login password change. Only a new password is needed:
 * the caller is already authenticated (they just logged in with a temporary
 * password), so no current password is re-collected.
 */
public record SetInitialPasswordRequest(
        @NotBlank(message = "New password is required")
        @StrongPassword
        String newPassword
) {
}
