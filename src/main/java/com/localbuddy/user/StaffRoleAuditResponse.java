package com.localbuddy.user;

import java.time.Instant;
import java.util.UUID;

/** One admin-team audit entry (GET /api/admin/users/team-audit). Actor null = SYSTEM. */
public record StaffRoleAuditResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        UUID targetUserId,
        String action,
        String detail,
        Instant createdAt
) {
}
