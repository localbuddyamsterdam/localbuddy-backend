package com.localbuddy.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The single admin-managed referral reward configuration (at most one row — a DB
 * unique index enforces the singleton). While {@link #active} and the current
 * time is within {@code [startsAt, endsAt]}, its {@link #rewardAmount} drives both
 * the referred user's booking discount and the referrer's reward; outside that
 * window the application falls back to the built-in default ({@code app.referral.*}).
 */
@Entity
@Table(name = "referral_reward_config")
@Getter
@Setter
@NoArgsConstructor
public class ReferralRewardConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "reward_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal rewardAmount;

    @Column(name = "reward_currency", nullable = false, length = 10)
    private String rewardCurrency = "EUR";

    /** NULL = effective immediately. */
    @Column(name = "starts_at")
    private Instant startsAt;

    /** NULL = no expiry. */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** Optional override of the per-referrer monthly cap; NULL = use the configured default. */
    @Column(name = "max_monthly_redemptions")
    private Integer maxMonthlyRedemptions;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether this config's reward amount is in force right now (active + inside the window). */
    public boolean isEffectiveAt(Instant now) {
        if (!active) {
            return false;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || !now.isAfter(endsAt);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (rewardCurrency == null) {
            rewardCurrency = "EUR";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
