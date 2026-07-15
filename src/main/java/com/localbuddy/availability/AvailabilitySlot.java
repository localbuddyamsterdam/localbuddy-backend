package com.localbuddy.availability;

import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "availability_slots")
@Getter
@Setter
@NoArgsConstructor
public class AvailabilitySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experience_id", nullable = false)
    private Experience experience;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_profile_id", nullable = false)
    private LocalProfile localProfile;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "capacity", nullable = false)
    private Integer capacity = 1;

    @Column(name = "booked_count", nullable = false)
    private Integer bookedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private AvailabilityStatus status = AvailabilityStatus.AVAILABLE;

    /** The recurring schedule that produced this slot, if any (null for one-off manual slots). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_schedule_id")
    private AvailabilitySchedule sourceSchedule;

    @Column(name = "private_eligible", nullable = false)
    private boolean privateEligible = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (capacity == null) {
            capacity = 1;
        }

        if (bookedCount == null) {
            bookedCount = 0;
        }

        if (status == null) {
            status = AvailabilityStatus.AVAILABLE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}