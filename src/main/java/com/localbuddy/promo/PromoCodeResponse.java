package com.localbuddy.promo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromoCodeResponse(
        UUID id,
        String code,
        String description,
        PromoDiscountType discountType,
        BigDecimal discountValue,
        String currency,
        BigDecimal maxDiscountAmount,
        BigDecimal minBookingAmount,
        Integer maxTotalRedemptions,
        Integer maxRedemptionsPerUser,
        Integer currentRedemptions,
        Instant startsAt,
        Instant expiresAt,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        List<UUID> experienceIds
) {
}