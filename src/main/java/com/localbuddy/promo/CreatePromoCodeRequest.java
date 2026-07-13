package com.localbuddy.promo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePromoCodeRequest(

        @NotBlank(message = "Promo code is required")
        @Size(max = 80, message = "Promo code cannot exceed 80 characters")
        String code,

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        String description,

        @NotNull(message = "Discount type is required")
        PromoDiscountType discountType,

        @NotNull(message = "Discount value is required")
        @DecimalMin(value = "0.01", message = "Discount value must be greater than zero")
        BigDecimal discountValue,

        @Size(max = 10, message = "Currency cannot exceed 10 characters")
        String currency,

        BigDecimal maxDiscountAmount,
        BigDecimal minBookingAmount,

        Integer maxTotalRedemptions,
        Integer maxRedemptionsPerUser,

        Instant startsAt,
        Instant expiresAt,

        Boolean active,

        /** Who bears the discount cost. Defaults to HOST (legacy behaviour) when omitted. */
        DiscountBearer discountBearer,

        /** Required when discountBearer is SPLIT: the platform's share of the discount (0–100). */
        BigDecimal platformSharePercentage,

        /** Optional voucher targeting: restrict redemption to this registered user. */
        UUID issuedToUserId,

        /** Optional voucher targeting: restrict redemption to this guest email. */
        @Size(max = 255, message = "Issued-to email cannot exceed 255 characters")
        String issuedToEmail,

        /** Whether this code may be stacked with other combinable codes on one booking. Defaults to false. */
        Boolean combinable,

        /** Experiences this code is limited to. Null/empty = valid on every experience. */
        List<UUID> experienceIds
) {
}