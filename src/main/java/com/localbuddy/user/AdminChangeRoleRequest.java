package com.localbuddy.user;

import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/admin/users/{id}/role — the role to assign (super admin only). */
public record AdminChangeRoleRequest(
        @NotNull(message = "role is required")
        UserRole role
) {
}
