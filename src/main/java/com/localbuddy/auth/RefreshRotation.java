package com.localbuddy.auth;

import com.localbuddy.user.User;

/** Result of rotating a refresh token: the owning user plus the freshly-issued replacement token. */
public record RefreshRotation(User user, String newRefreshToken) {
}
