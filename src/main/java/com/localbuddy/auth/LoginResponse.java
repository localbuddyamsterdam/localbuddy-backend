package com.localbuddy.auth;

import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String firstName,
        String lastName,
        String preferredName,
        String email,
        UserRole role,
        UserStatus status,
        /** When true, the client must route the user through the forced "set your password" screen. */
        boolean mustChangePassword,
        /** True when this sign-in just created the account (first-time social login) so the client
         *  can collect the remaining profile details. Always false for email login and refresh. */
        boolean newUser
) {
}