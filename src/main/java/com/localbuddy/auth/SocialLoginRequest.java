package com.localbuddy.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Social sign-in: the frontend completes the provider flow and sends the
 * resulting token (Google/Apple ID token, or Facebook access token); the backend
 * verifies it with the provider and issues a LocalBuddy access token.
 */
public record SocialLoginRequest(

        @NotNull(message = "Provider is required")
        SocialProvider provider,

        @NotBlank(message = "Token is required")
        String token
) {
}
