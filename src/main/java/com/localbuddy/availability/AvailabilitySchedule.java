package com.localbuddy.availability;

import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A host's persistent, editable weekly availability pattern for one experience —
 * the source of truth that a scheduled job materialises into concrete
 * {@link AvailabilitySlot} rows up to a rolling horizon.
 */
@Entity
@Table(name = "availability_schedules")
@Getter
@Setter
@NoArgsConstructor
public class AvailabilitySchedule {

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

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "capacity", nullable = false)
    private Integer capacity = 1;

    @Column(name = "private_eligible", nullable = false)
    private boolean privateEligible = false;

    /** IANA zone the pattern's wall-clock times are expressed in (snapshot of the city's zone at creation). */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Europe/Amsterdam";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduleStatus status = ScheduleStatus.ACTIVE;

    /** Date through which concrete slots have already been materialised. */
    @Column(name = "materialized_until")
    private LocalDate materializedUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "availability_schedule_times", joinColumns = @JoinColumn(name = "schedule_id"))
    private List<ScheduleTime> times = new ArrayList<>();

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
        if (status == null) {
            status = ScheduleStatus.ACTIVE;
        }
        if (timezone == null || timezone.isBlank()) {
            timezone = "Europe/Amsterdam";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
