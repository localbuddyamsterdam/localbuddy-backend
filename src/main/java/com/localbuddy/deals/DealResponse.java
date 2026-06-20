package com.localbuddy.deals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DealResponse(
        UUID id,
        String name,
        String description,
        DealType dealType,
        DealDiscountType discountType,
        BigDecimal discountValue,
        String currency,
        DealScope scope,
        UUID targetCityId,
        UUID targetExperienceId,
        UUID targetCategoryId,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        Integer priority,
        String badgeText,
        Instant createdAt,
        Instant updatedAt
) {
}
