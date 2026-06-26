package com.localbuddy.experience;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record CreateExperienceRequest(

        @NotNull(message = "Category is required")
        UUID categoryId,

        Set<UUID> categoryIds,

        @NotNull(message = "City is required")
        UUID cityId,

        @NotBlank(message = "Title is required")
        @Size(max = 150, message = "Title cannot exceed 150 characters")
        String title,

        @NotBlank(message = "Description is required")
        @Size(max = 3000, message = "Description cannot exceed 3000 characters")
        String description,

        @Size(max = 150, message = "Meeting area cannot exceed 150 characters")
        String meetingArea,

        @NotNull(message = "Duration is required")
        @Min(value = 30, message = "Duration must be at least 30 minutes")
        @Max(value = 720, message = "Duration cannot exceed 720 minutes")
        Integer durationMinutes,

        @DecimalMin(value = "0.00", message = "Price cannot be negative")
        BigDecimal priceAmount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        @NotNull(message = "Max guests is required")
        @Min(value = 1, message = "Max guests must be at least 1")
        @Max(value = 10, message = "Max guests cannot exceed 10 for MVP")
        Integer maxGuests,

        /** Optional meeting-point coordinates (set together). */
        @DecimalMin(value = "-90.0", message = "Latitude out of range")
        @DecimalMax(value = "90.0", message = "Latitude out of range")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "Longitude out of range")
        @DecimalMax(value = "180.0", message = "Longitude out of range")
        BigDecimal longitude,

        @Size(max = 2000, message = "Safety notes cannot exceed 2000 characters")
        String safetyNotes,

        @Size(max = 300, message = "Short description cannot exceed 300 characters")
        String shortDescription,

        TransportMode transportMode,

        @Size(max = 2000, message = "Inclusions cannot exceed 2000 characters")
        String inclusions,

        @Size(max = 2000, message = "Exclusions cannot exceed 2000 characters")
        String exclusions,

        @Size(max = 255, message = "End location cannot exceed 255 characters")
        String endLocation,

        @Size(max = 2000, message = "Reasons to book cannot exceed 2000 characters")
        String reasonsToBook,

        @Min(value = 0, message = "Minimum age cannot be negative")
        @Max(value = 120, message = "Minimum age cannot exceed 120")
        Integer minimumAge,

        BookingMode bookingMode,

        @DecimalMin(value = "0.00", message = "Private price cannot be negative")
        BigDecimal privatePrice,

        /** Whether priceAmount is the gross (customer) price or the host's net; the other is computed. */
        PriceInputMode priceInputMode,

        /** Where the host also offers this experience: NONE, OWN_WEBSITE_SOCIAL, or AGGREGATOR_PLATFORM.
         *  Private-buyout bookings are blocked only for AGGREGATOR_PLATFORM. Defaults to NONE. */
        ExternalListingType externalListingType,

        @Size(max = 500, message = "External listing details cannot exceed 500 characters")
        String externalListingDetails
) {
}