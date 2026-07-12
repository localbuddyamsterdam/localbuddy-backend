package com.localbuddy.auth;

import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        List<String> languages,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        boolean phoneVerified
) {
}
