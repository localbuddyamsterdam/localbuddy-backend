package com.localbuddy.pricing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** VAT rate for a place of supply (country) and optional experience category. */
@Entity
@Table(name = "vat_rates")
@Getter
@Setter
@NoArgsConstructor
public class VatRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    /** Experience category this rate applies to; null = the country's default rate. */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    @Column(name = "rate_kind", nullable = false, length = 20)
    private String rateKind = "STANDARD";

    @Column(name = "description", length = 120)
    private String description;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (effectiveFrom == null) {
            effectiveFrom = now;
        }
    }
}
