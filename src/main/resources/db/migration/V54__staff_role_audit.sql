-- =====================================================================
-- Staff role audit — an append-only record of every change to the admin
-- team: staff account created, admin promoted to super admin, super admin
-- demoted, staff removed, temporary password issued, and bootstrap/break-
-- glass actions performed by the system itself (actor NULL = SYSTEM).
--
-- This is part of the super admin safety model: a super admin can only be
-- removed by ANOTHER super admin (never themselves), so every removal is
-- attributable here to a surviving actor. Mirrors booking_change_audit (V35).
-- =====================================================================
CREATE TABLE staff_role_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   UUID,                            -- who did it (users.id); NULL = SYSTEM (bootstrap/break-glass)
    target_user_id  UUID        NOT NULL,            -- whose account was affected (users.id)
    action          VARCHAR(40) NOT NULL,            -- STAFF_CREATED|ROLE_CHANGED|STAFF_REMOVED|TEMP_PASSWORD_ISSUED|BOOTSTRAP_CREATED|BOOTSTRAP_PROMOTED|BREAK_GLASS_RESET
    detail          VARCHAR(300),                    -- human-readable summary (e.g. "ADMIN -> SUPER_ADMIN")
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_staff_role_audit_created ON staff_role_audit (created_at DESC);
CREATE INDEX idx_staff_role_audit_target  ON staff_role_audit (target_user_id, created_at DESC);
