package com.localbuddy.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String firstName,
        String lastName,
        String preferredName,
        String email,
        String phone,
        String avatarUrl,
        java.util.List<String> languages,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt
) {
}