package com.localbuddy.giftcard;

import java.math.BigDecimal;
import java.time.Instant;

public record GiftCardBalanceResponse(
        String code,
        BigDecimal balance,
        String currency,
        GiftCardStatus status,
        Instant expiresAt
) {
    public static GiftCardBalanceResponse from(GiftCard card) {
        return new GiftCardBalanceResponse(
                card.getCode(),
                card.getBalance(),
                card.getCurrency(),
                card.getStatus(),
                card.getExpiresAt()
        );
    }
}
