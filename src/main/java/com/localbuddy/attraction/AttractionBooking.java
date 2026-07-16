package com.localbuddy.attraction;

import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A third-party attraction ticket order placed through a distributor API (Tiqets), recorded
 * locally so the traveler sees it under their trips and support can trace provider order ids.
 * Affiliate link-out sales are NOT recorded here — they happen entirely on the provider's site.
 */
@Entity
@Table(name = "attraction_bookings")
@Getter
@Setter
@NoArgsConstructor
public class AttractionBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** In-app attraction booking requires login — there is always an owner. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider = "TIQETS";

    @Column(name = "product_id", nullable = false, length = 80)
    private String productId;

    @Column(name = "product_title", nullable = false, length = 200)
    private String productTitle;

    @Column(name = "city_slug", length = 120)
    private String citySlug;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** Provider timeslot id (entry window), null for all-day tickets. */
    @Column(name = "timeslot", length = 60)
    private String timeslot;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AttractionBookingStatus status = AttractionBookingStatus.PENDING;

    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    /** Where the traveler's tickets live (provider download/wallet page). */
    @Column(name = "ticket_url", length = 1000)
    private String ticketUrl;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

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
            status = AttractionBookingStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
