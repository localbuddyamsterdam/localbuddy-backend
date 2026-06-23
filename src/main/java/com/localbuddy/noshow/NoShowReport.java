package com.localbuddy.noshow;

import com.localbuddy.booking.Booking;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A complaint that one party did not show up for a booking, filed within 48h of the experience
 * start and verified by an admin. A HOST report (filed by the customer) refunds the customer
 * in full on approval; a CUSTOMER report (filed by the host) is informational only.
 */
@Entity
@Table(name = "no_show_reports")
@Getter
@Setter
@NoArgsConstructor
public class NoShowReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** The user who filed the report. Null when a guest customer reports (future guest flow). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_user_id")
    private User reportedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject", nullable = false, length = 20)
    private NoShowSubject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NoShowReportStatus status = NoShowReportStatus.REQUESTED;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = NoShowReportStatus.REQUESTED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
