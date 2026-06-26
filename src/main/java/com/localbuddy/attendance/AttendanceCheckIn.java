package com.localbuddy.attendance;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.booking.Booking;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A geo check-in by a host or guest near the meeting point around the experience start.
 * Host check-ins are slot-level (booking null); guest check-ins are booking-level. Operational +
 * a soft signal — not an auto-refund decision. Distance is to the experience's meeting-point coordinate.
 */
@Entity
@Table(name = "attendance_check_ins")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "availability_slot_id", nullable = false)
    private AvailabilitySlot availabilitySlot;

    /** Null for a host check-in (slot-level). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    /** Null for an anonymous guest check-in (then guestEmail is set). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private CheckInRole role;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Device-reported horizontal accuracy in metres. */
    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    /** Distance to the meeting point in metres; null if the experience has no coordinate. */
    @Column(name = "distance_meters")
    private Double distanceMeters;

    @Column(name = "within_geofence", nullable = false)
    private boolean withinGeofence = false;

    /** Optional live arrival photo (host only). */
    @Column(name = "photo_url", length = 2000)
    private String photoUrl;

    @Column(name = "photo_storage_key", length = 500)
    private String photoStorageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
