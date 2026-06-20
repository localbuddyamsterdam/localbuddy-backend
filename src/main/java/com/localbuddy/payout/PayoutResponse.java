package com.localbuddy.payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        UUID id,
        UUID localProfileId,
        BigDecimal amount,
        String currency,
        PayoutStatus status,
        String providerTransferId,
        String notes,
        Instant createdAt,
        Instant paidAt
) {
}
