package com.localbuddy.tripplan;

import com.localbuddy.experience.City;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A saved AI-generated itinerary, publicly addressable by its unguessable token
 * (the "book my whole trip" share link). The enriched plan document — days, items,
 * links, slot ids, prices — is stored verbatim as JSON exactly as it is served.
 */
@Entity
@Table(name = "trip_plans")
@Getter
@Setter
@NoArgsConstructor
public class TripPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, length = 64, updatable = false)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /** Owning traveler, set only when generation required login (older/guest plans have none). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "party_size", nullable = false)
    private Integer partySize = 2;

    @Column(name = "interests", length = 500)
    private String interests;

    @Column(name = "notes", length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TripPlanStatus status = TripPlanStatus.ACTIVE;

    /** Enriched plan document (TripPlanDocument) serialized as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", nullable = false, columnDefinition = "jsonb")
    private String plan;

    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = TripPlanStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
