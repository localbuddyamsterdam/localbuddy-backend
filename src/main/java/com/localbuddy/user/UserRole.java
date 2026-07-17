package com.localbuddy.user;

public enum UserRole {
    LOGGED_IN_USER,
    LOCAL,
    ADMIN,
    SUPPORT,
    /**
     * The platform's root tier: everything ADMIN can do, plus managing the admin
     * team itself (creating/removing admins, promoting/demoting super admins).
     * Never assignable through public signup or by plain admins — only an existing
     * super admin (or the {@code SuperAdminBootstrap} seeder) can grant it.
     * {@code JwtAuthenticationFilter} grants SUPER_ADMIN both ROLE_SUPER_ADMIN and
     * ROLE_ADMIN authorities so every existing admin gate applies automatically.
     */
    SUPER_ADMIN
}