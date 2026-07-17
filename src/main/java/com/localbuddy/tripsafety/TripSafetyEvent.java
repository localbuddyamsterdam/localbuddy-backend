package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip_safety_events")
@Getter
@Setter
@NoArgsConstructor
public class TripSafetyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private TripSafetyEventType eventType;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // --- SOS enrichment (left null for CHECK_IN / CHECK_OUT events) ---

    @Enumerated(EnumType.STRING)
    @Column(name = "situation_type", length = 40)
    private SosSituationType situationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_preference", length = 40)
    private SosContactPreference contactPreference;

    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    @Column(name = "location_source", length = 20)
    private String locationSource;

    @Column(name = "battery_percent")
    private Integer batteryPercent;

    @Column(name = "device_language", length = 20)
    private String deviceLanguage;

    /** Reverse-geocoded street address, populated later; null until then. */
    @Column(name = "geocoded_address", length = 500)
    private String geocodedAddress;

    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // --- Admin incident lifecycle (SOS) ---

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
