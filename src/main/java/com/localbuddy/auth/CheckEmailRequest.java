package com.localbuddy.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/auth/check-email} — the first step of the email-first
 * sign-in flow. The client sends just the email to learn whether it already has
 * an account (show a password step) or not (show a sign-up step).
 */
public record CheckEmailRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email cannot exceed 255 characters")
        String email
) {
}
