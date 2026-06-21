package com.localbuddy.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VatRateResponse(
        UUID id,
        String country,
        UUID categoryId,
        BigDecimal rate,
        String rateKind,
        String description,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active
) {
}
