package com.localbuddy.deals;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UpdateDealRequest(

        @NotBlank(message = "Deal name is required")
        @Size(max = 200, message = "Deal name cannot exceed 200 characters")
        String name,

        @Size(max = 5000, message = "Description cannot exceed 5000 characters")
        String description,

        @NotNull(message = "Deal type is required")
        DealType dealType,

        @NotNull(message = "Discount type is required")
        DealDiscountType discountType,

        @NotNull(message = "Discount value is required")
        @DecimalMin(value = "0.00", message = "Discount value cannot be negative")
        BigDecimal discountValue,

        @Size(max = 10, message = "Currency cannot exceed 10 characters")
        String currency,

        @NotNull(message = "Scope is required")
        DealScope scope,

        UUID targetCityId,
        UUID targetExperienceId,
        UUID targetCategoryId,

        Instant startsAt,
        Instant endsAt,

        Integer priority,

        @Size(max = 80, message = "Badge text cannot exceed 80 characters")
        String badgeText,

        Boolean active
) {
}
