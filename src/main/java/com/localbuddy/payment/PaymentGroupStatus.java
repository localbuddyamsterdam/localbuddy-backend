package com.localbuddy.payment;

/**
 * Lifecycle of a payment group (one Stripe checkout covering several bookings).
 * REFUNDED is the terminal state of a late payment that landed after the group
 * was already cancelled and was returned to the customer in full. REFUND_FAILED
 * flags a late-payment refund the provider rejected — the customer's cash is
 * still held and the refund must be retried (admin/reconciliation).
 */
public enum PaymentGroupStatus {
    PENDING,
    PROCESSING,
    PAID,
    FAILED,
    CANCELLED,
    REFUNDED,
    REFUND_FAILED
}
