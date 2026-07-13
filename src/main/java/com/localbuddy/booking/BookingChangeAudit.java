package com.localbuddy.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit trail of admin actions on a booking (cancel, edit, party, reschedule,
 * attendance, complete, create) — powers the admin "Manage booking" History tab.
 * Mirrors {@code RateChangeAudit}. Written by {@link BookingAuditService}.
 */
@Entity
@Table(name = "booking_change_audit")
@Getter
@Setter
@NoArgsConstructor
public class BookingChangeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** The acting admin (users.id); resolved to a display name on read. */
    @Column(name = "changed_by_user_id")
    private UUID changedByUserId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }
}
