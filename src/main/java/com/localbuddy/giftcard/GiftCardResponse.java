package com.localbuddy.giftcard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GiftCardResponse(
        UUID id,
        String code,
        BigDecimal initialAmount,
        BigDecimal balance,
        String currency,
        GiftCardStatus status,
        String recipientEmail,
        String recipientName,
        String message,
        Instant expiresAt,
        Instant createdAt
) {
    public static GiftCardResponse from(GiftCard card) {
        return new GiftCardResponse(
                card.getId(),
                card.getCode(),
                card.getInitialAmount(),
                card.getBalance(),
                card.getCurrency(),
                card.getStatus(),
                card.getRecipientEmail(),
                card.getRecipientName(),
                card.getMessage(),
                card.getExpiresAt(),
                card.getCreatedAt()
        );
    }
}
