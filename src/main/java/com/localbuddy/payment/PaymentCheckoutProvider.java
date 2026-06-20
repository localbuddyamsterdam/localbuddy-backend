package com.localbuddy.payment;

import java.math.BigDecimal;

public interface PaymentCheckoutProvider {

    PaymentProvider getProvider();

    PaymentCheckoutResult createCheckout(Payment payment);

    PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason);

    /**
     * Best-effort: expire/void a checkout session so it can no longer be paid
     * after the seat has been released. Implementations must not throw.
     */
    void expireCheckout(String providerCheckoutSessionId);
}