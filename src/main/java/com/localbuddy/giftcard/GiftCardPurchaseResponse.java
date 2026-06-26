package com.localbuddy.giftcard;

import java.math.BigDecimal;
import java.util.UUID;

/** Returned when purchasing a gift card: the (pending) card plus the Stripe checkout URL to pay for it. */
public record GiftCardPurchaseResponse(
        UUID giftCardId,
        String code,
        BigDecimal amount,
        String currency,
        GiftCardStatus status,
        String checkoutUrl
) {
}
