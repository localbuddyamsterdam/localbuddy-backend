package com.localbuddy.experience;

import com.localbuddy.localprofile.LocalProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "experiences")
@Getter
@Setter
@NoArgsConstructor
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_profile_id", nullable = false)
    private LocalProfile localProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ExperienceCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /** Full set of categories this experience belongs to (in addition to the primary {@link #category}). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "experience_category_links",
            joinColumns = @JoinColumn(name = "experience_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<ExperienceCategory> categories = new LinkedHashSet<>();

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "slug", nullable = false, unique = true, length = 180)
    private String slug;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "short_description", length = 300)
    private String shortDescription;

    @Column(name = "meeting_area", length = 150)
    private String meetingArea;

    @Column(name = "end_location", length = 255)
    private String endLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 40)
    private TransportMode transportMode;

    @Column(name = "inclusions", columnDefinition = "TEXT")
    private String inclusions;

    @Column(name = "exclusions", columnDefinition = "TEXT")
    private String exclusions;

    @Column(name = "reasons_to_book", columnDefinition = "TEXT")
    private String reasonsToBook;

    @Column(name = "minimum_age", nullable = false)
    private Integer minimumAge = 0;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "price_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_mode", nullable = false, length = 40)
    private BookingMode bookingMode = BookingMode.SHARED;

    @Column(name = "private_price", precision = 10, scale = 2)
    private BigDecimal privatePrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "max_guests", nullable = false)
    private Integer maxGuests;

    @Column(name = "safety_notes", columnDefinition = "TEXT")
    private String safetyNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ExperienceStatus status = ExperienceStatus.DRAFT;

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

        if (currency == null || currency.trim().isEmpty()) {
            currency = "EUR";
        }

        if (status == null) {
            status = ExperienceStatus.DRAFT;
        }

        if (minimumAge == null) {
            minimumAge = 0;
        }

        if (bookingMode == null) {
            bookingMode = BookingMode.SHARED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}