package com.localbuddy.deals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The single best currently-live deal resolved for a specific experience, with
 * the computed discounted price. Experience-scoped deals beat category, then
 * city, then global.
 */
public record ResolvedDealResponse(
        UUID dealId,
        String name,
        String badgeText,
        DealDiscountType discountType,
        BigDecimal discountValue,
        BigDecimal originalPrice,
        BigDecimal discountAmount,
        BigDecimal finalPrice,
        String currency
) {
}
