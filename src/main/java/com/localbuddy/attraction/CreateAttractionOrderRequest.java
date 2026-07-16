package com.localbuddy.attraction;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * In-app attraction ticket order. Customer identity comes from the authenticated user, never
 * the request. {@code productTitle} is only a display label for the traveler's own record —
 * the provider order is keyed on {@code productId} alone.
 */
public record CreateAttractionOrderRequest(

        @NotBlank(message = "Product is required")
        @Size(max = 80, message = "Product id cannot exceed 80 characters")
        String productId,

        @NotBlank(message = "Product title is required")
        @Size(max = 200, message = "Product title cannot exceed 200 characters")
        String productTitle,

        @Size(max = 120, message = "City slug cannot exceed 120 characters")
        String citySlug,

        @NotNull(message = "Visit date is required")
        LocalDate visitDate,

        @Size(max = 60, message = "Timeslot cannot exceed 60 characters")
        String timeslotId,

        @NotNull(message = "Ticket quantity is required")
        @Min(value = 1, message = "At least 1 ticket")
        @Max(value = 20, message = "At most 20 tickets")
        Integer quantity
) {
}
