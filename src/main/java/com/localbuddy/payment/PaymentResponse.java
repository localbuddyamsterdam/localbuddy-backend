package com.localbuddy.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID bookingId,
        PaymentProvider provider,
        PaymentMethodType paymentMethodType,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        String currency,
        BigDecimal platformFeeAmount,
        BigDecimal localPayoutAmount,
        String providerCheckoutSessionId,
        String providerPaymentIntentId,
        String checkoutUrl,
        Instant paidAt,
        Instant failedAt,
        Instant cancelledAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt,
        // Refund detail so the admin console can show how much was returned and why.
        BigDecimal refundedAmount,
        String refundReason
) {
}