package com.localbuddy.waitlist;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.experience.Experience;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slot_waitlist_entries")
@Getter
@Setter
@NoArgsConstructor
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "availability_slot_id", nullable = false)
    private AvailabilitySlot availabilitySlot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experience_id", nullable = false)
    private Experience experience;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_first_name", length = 100)
    private String guestFirstName;

    @Column(name = "guest_last_name", length = 100)
    private String guestLastName;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Column(name = "guest_phone", length = 40)
    private String guestPhone;

    @Column(name = "guests_count", nullable = false)
    private Integer guestsCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private WaitlistStatus status = WaitlistStatus.WAITING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (guestsCount == null) {
            guestsCount = 1;
        }
        if (status == null) {
            status = WaitlistStatus.WAITING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Combined guest name (first + last), or {@code null} for a logged-in entry. Not a mapped column. */
    public String getGuestName() {
        if (guestFirstName == null && guestLastName == null) {
            return null;
        }
        String first = guestFirstName == null ? "" : guestFirstName;
        String last = guestLastName == null ? "" : guestLastName;
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? null : combined;
    }
}
