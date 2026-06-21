package com.localbuddy.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateBookingRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId,

        @NotNull(message = "Availability slot id is required")
        UUID availabilitySlotId,

        @NotNull(message = "Guests count is required")
        @Min(value = 1, message = "Guests count must be at least 1")
        @Max(value = 10, message = "Guests count cannot exceed 10")
        Integer guestsCount,

        @Min(value = 0, message = "Adults cannot be negative")
        Integer adults,

        @Min(value = 0, message = "Teens cannot be negative")
        Integer teens,

        @Min(value = 0, message = "Children cannot be negative")
        Integer children,

        @Min(value = 0, message = "Infants cannot be negative")
        Integer infants,

        @Size(max = 1000, message = "Traveler note cannot exceed 1000 characters")
        String travelerNote,

        @Size(max = 80, message = "Promo code cannot exceed 80 characters")
        String promoCode,

        @Size(max = 80, message = "Referral code cannot exceed 80 characters")
        String referralCode,

        Boolean privateBooking
) {
}