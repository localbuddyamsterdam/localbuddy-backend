package com.localbuddy.promo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "promo_codes")
@Getter
@Setter
@NoArgsConstructor
public class PromoCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 80)
    private String code;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 40)
    private PromoDiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "min_booking_amount", precision = 10, scale = 2)
    private BigDecimal minBookingAmount;

    @Column(name = "max_total_redemptions")
    private Integer maxTotalRedemptions;

    @Column(name = "max_redemptions_per_user")
    private Integer maxRedemptionsPerUser;

    @Column(name = "current_redemptions", nullable = false)
    private Integer currentRedemptions = 0;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_bearer", nullable = false, length = 20)
    private DiscountBearer discountBearer = DiscountBearer.HOST;

    /** For SPLIT: percentage (0–100) of the discount the platform absorbs; the host bears the rest. */
    @Column(name = "platform_share_percentage", precision = 5, scale = 2)
    private BigDecimal platformSharePercentage;

    /** Optional targeting: only this registered user may redeem the voucher. */
    @Column(name = "issued_to_user_id")
    private UUID issuedToUserId;

    /** Optional targeting: only this (guest) email may redeem the voucher. */
    @Column(name = "issued_to_email", length = 255)
    private String issuedToEmail;

    /**
     * Whether this code may be stacked with other codes on one booking. Multiple codes
     * can be applied together only when every applied code is combinable. Default false
     * (exclusive — cannot be combined).
     */
    @Column(name = "combinable", nullable = false)
    private boolean combinable = false;

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

        if (currentRedemptions == null) {
            currentRedemptions = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}