package com.localbuddy.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PaymentCheckoutProvider {

    PaymentProvider getProvider();

    PaymentCheckoutResult createCheckout(Payment payment);

    /** Creates a checkout session to purchase a gift card; the session carries the gift card id in metadata. */
    PaymentCheckoutResult createGiftCardCheckout(UUID giftCardId, BigDecimal amount, String currency, String reference);

    PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason);

    /**
     * Creates ONE checkout session paying for all member payments of a bundle. The session
     * and payment-intent ids belong to the group; member payments keep theirs NULL.
     * Default throws — a provider must opt in to bundle checkouts explicitly.
     */
    default PaymentCheckoutResult createGroupCheckout(PaymentGroup group, List<Payment> payments) {
        throw new UnsupportedOperationException("This payment provider does not support bundle checkouts");
    }

    /**
     * Refunds cash captured on a group's shared payment intent without reference to a single
     * member payment — used when a late payment lands on an already-cancelled group.
     * Default throws — a provider must opt in to bundle checkouts explicitly.
     */
    default PaymentRefundResult refundGroupCash(PaymentGroup group, BigDecimal refundAmount, String reason) {
        throw new UnsupportedOperationException("This payment provider does not support bundle checkouts");
    }

    /**
     * Best-effort: expire/void a checkout session so it can no longer be paid
     * after the seat has been released. Implementations must not throw.
     */
    void expireCheckout(String providerCheckoutSessionId);
}