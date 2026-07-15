package com.localbuddy.payment;

import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money parent for a bundle checkout: one Stripe checkout session paying for several
 * bookings at once (e.g. "book my whole AI trip plan"). Each member booking keeps its
 * own {@link Payment} row with the full per-booking financial snapshot; children point
 * here via payment_group_id, and the Stripe session / payment-intent ids live on the
 * group only (children keep theirs NULL so the payments partial-unique indexes hold).
 */
@Entity
@Table(name = "payment_groups")
@Getter
@Setter
@NoArgsConstructor
public class PaymentGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "group_token", nullable = false, length = 64, updatable = false)
    private String groupToken;

    @Column(name = "trip_plan_id")
    private UUID tripPlanId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "logged_in_user_id")
    private User loggedInUser;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentGroupStatus status = PaymentGroupStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 40)
    private PaymentProvider provider = PaymentProvider.STRIPE;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "gift_card_id")
    private UUID giftCardId;

    /**
     * Original amount drawn from the gift card for the whole group. Immutable once set —
     * the failure-path return is guarded by {@link #giftCardReturnedAt}, not by zeroing,
     * because this value is also the basis for "cash actually captured" refund math.
     */
    @Column(name = "gift_card_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal giftCardAmount = BigDecimal.ZERO;

    @Column(name = "gift_card_returned_at")
    private Instant giftCardReturnedAt;

    @Column(name = "provider_checkout_session_id", length = 255)
    private String providerCheckoutSessionId;

    @Column(name = "provider_payment_intent_id", length = 255)
    private String providerPaymentIntentId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

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
            status = PaymentGroupStatus.PENDING;
        }
        if (provider == null) {
            provider = PaymentProvider.STRIPE;
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
        if (giftCardAmount == null) {
            giftCardAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
