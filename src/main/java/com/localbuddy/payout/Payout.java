package com.localbuddy.payout;

import com.localbuddy.localprofile.LocalProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_profile_id", nullable = false)
    private LocalProfile localProfile;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private PayoutStatus status = PayoutStatus.PENDING;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider = "STRIPE";

    @Column(name = "provider_transfer_id", length = 255)
    private String providerTransferId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = PayoutStatus.PENDING;
        }
        if (currency == null || currency.trim().isEmpty()) {
            currency = "EUR";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
