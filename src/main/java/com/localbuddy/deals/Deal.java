package com.localbuddy.deals;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deals")
@Getter
@Setter
@NoArgsConstructor
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 40)
    private DealType dealType;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 40)
    private DealDiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "currency", length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 40)
    private DealScope scope;

    @Column(name = "target_city_id")
    private UUID targetCityId;

    @Column(name = "target_experience_id")
    private UUID targetExperienceId;

    @Column(name = "target_category_id")
    private UUID targetCategoryId;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Column(name = "badge_text", length = 80)
    private String badgeText;

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

        if (priority == null) {
            priority = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
