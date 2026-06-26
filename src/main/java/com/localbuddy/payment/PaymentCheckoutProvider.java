package com.localbuddy.payment;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentCheckoutProvider {

    PaymentProvider getProvider();

    PaymentCheckoutResult createCheckout(Payment payment);

    /** Creates a checkout session to purchase a gift card; the session carries the gift card id in metadata. */
    PaymentCheckoutResult createGiftCardCheckout(UUID giftCardId, BigDecimal amount, String currency, String reference);

    PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason);

    /**
     * Best-effort: expire/void a checkout session so it can no longer be paid
     * after the seat has been released. Implementations must not throw.
     */
    void expireCheckout(String providerCheckoutSessionId);
}