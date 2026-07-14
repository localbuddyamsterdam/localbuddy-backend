package com.localbuddy.auth;

import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;

import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String firstName,
        String lastName,
        String email,
        UserRole role,
        UserStatus status,
        String message
) {
}