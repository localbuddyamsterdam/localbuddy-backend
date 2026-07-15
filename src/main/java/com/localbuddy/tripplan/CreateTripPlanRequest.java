package com.localbuddy.tripplan;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateTripPlanRequest(

        @NotBlank(message = "City is required")
        @Size(max = 120, message = "City slug cannot exceed 120 characters")
        String citySlug,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate,

        @NotNull(message = "Party size is required")
        @Min(value = 1, message = "Party size must be at least 1")
        @Max(value = 10, message = "Party size cannot exceed 10")
        Integer partySize,

        @Size(max = 500, message = "Interests cannot exceed 500 characters")
        String interests,

        @Size(max = 500, message = "Notes cannot exceed 500 characters")
        String notes
) {
}
