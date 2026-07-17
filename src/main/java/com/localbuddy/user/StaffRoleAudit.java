package com.localbuddy.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of admin-team changes (staff created, role changed, staff
 * removed, temp password issued, bootstrap actions). Every mutation of the
 * privileged set is attributable: super admins can never remove themselves, so
 * each removal names a surviving actor. Mirrors {@code BookingChangeAudit}.
 */
@Entity
@Table(name = "staff_role_audit")
@Getter
@Setter
@NoArgsConstructor
public class StaffRoleAudit {

    /** Action names stored in the {@code action} column. */
    public static final String STAFF_CREATED = "STAFF_CREATED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String STAFF_REMOVED = "STAFF_REMOVED";
    public static final String TEMP_PASSWORD_ISSUED = "TEMP_PASSWORD_ISSUED";
    public static final String BOOTSTRAP_CREATED = "BOOTSTRAP_CREATED";
    public static final String BOOTSTRAP_PROMOTED = "BOOTSTRAP_PROMOTED";
    public static final String BREAK_GLASS_RESET = "BREAK_GLASS_RESET";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The acting user (users.id); null when the system itself acted (bootstrap/break-glass). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "detail", length = 300)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public StaffRoleAudit(UUID actorUserId, UUID targetUserId, String action, String detail) {
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.action = action;
        this.detail = detail;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
