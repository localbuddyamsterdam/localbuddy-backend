package com.localbuddy.giftcard;

import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gift_cards")
@Getter
@Setter
@NoArgsConstructor
public class GiftCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "initial_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal initialAmount;

    @Column(name = "balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal balance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private GiftCardStatus status = GiftCardStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaser_user_id")
    private User purchaserUser;

    @Column(name = "purchaser_email", length = 255)
    private String purchaserEmail;

    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "expires_at")
    private Instant expiresAt;

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
        if (status == null) {
            status = GiftCardStatus.ACTIVE;
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
