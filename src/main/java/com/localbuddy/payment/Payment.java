package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 40)
    private PaymentProvider provider = PaymentProvider.STRIPE;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method_type", nullable = false, length = 60)
    private PaymentMethodType paymentMethodType = PaymentMethodType.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 40)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "platform_fee_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal platformFeeAmount = BigDecimal.ZERO;

    @Column(name = "local_payout_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal localPayoutAmount = BigDecimal.ZERO;

    // ---- Financial snapshot (resolved at booking time; immutable) ----

    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(name = "commission_vat_rate", precision = 5, scale = 4)
    private BigDecimal commissionVatRate;

    @Column(name = "commission_vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionVatAmount = BigDecimal.ZERO;

    @Column(name = "commission_vat_treatment", length = 20)
    private String commissionVatTreatment;

    @Column(name = "service_fee_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal serviceFeeAmount = BigDecimal.ZERO;

    @Column(name = "service_fee_vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal serviceFeeVatAmount = BigDecimal.ZERO;

    @Column(name = "experience_gross_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal experienceGrossAmount = BigDecimal.ZERO;

    @Column(name = "experience_net_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal experienceNetAmount = BigDecimal.ZERO;

    @Column(name = "experience_vat_rate", precision = 5, scale = 4)
    private BigDecimal experienceVatRate;

    @Column(name = "experience_vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal experienceVatAmount = BigDecimal.ZERO;

    @Column(name = "place_of_supply_country", length = 2)
    private String placeOfSupplyCountry;

    @Column(name = "host_payout_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal hostPayoutAmount = BigDecimal.ZERO;

    @Column(name = "provider_checkout_session_id")
    private String providerCheckoutSessionId;

    @Column(name = "provider_payment_intent_id")
    private String providerPaymentIntentId;

    @Column(name = "provider_charge_id")
    private String providerChargeId;

    @Column(name = "provider_customer_id")
    private String providerCustomerId;

    @Column(name = "provider_payment_method_id")
    private String providerPaymentMethodId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "refund_reason", columnDefinition = "TEXT")
    private String refundReason;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "refunded_amount", precision = 10, scale = 2)
    private BigDecimal refundedAmount;


    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

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

        if (provider == null) {
            provider = PaymentProvider.STRIPE;
        }

        if (paymentMethodType == null) {
            paymentMethodType = PaymentMethodType.UNKNOWN;
        }

        if (paymentStatus == null) {
            paymentStatus = PaymentStatus.PENDING;
        }

        if (platformFeeAmount == null) {
            platformFeeAmount = BigDecimal.ZERO;
        }

        if (localPayoutAmount == null) {
            localPayoutAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}