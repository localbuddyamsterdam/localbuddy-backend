package com.localbuddy.auth;

import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String fullName,
        String email,
        UserRole role,
        UserStatus status
) {
}