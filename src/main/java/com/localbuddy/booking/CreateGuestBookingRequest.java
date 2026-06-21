package com.localbuddy.booking;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateGuestBookingRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId,

        @NotNull(message = "Availability slot id is required")
        UUID availabilitySlotId,

        @NotNull(message = "Guests count is required")
        @Min(value = 1, message = "Guests count must be at least 1")
        Integer guestsCount,

        @Min(value = 0, message = "Adults cannot be negative")
        Integer adults,

        @Min(value = 0, message = "Teens cannot be negative")
        Integer teens,

        @Min(value = 0, message = "Children cannot be negative")
        Integer children,

        @Min(value = 0, message = "Infants cannot be negative")
        Integer infants,

        @NotBlank(message = "Guest name is required")
        @Size(max = 150, message = "Guest name cannot exceed 150 characters")
        String guestName,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @NotBlank(message = "Guest phone is required")
        @Size(max = 30, message = "Guest phone cannot exceed 30 characters")
        String guestPhone,

        @Size(max = 1000, message = "Traveler note cannot exceed 1000 characters")
        String travelerNote,

        @Size(max = 80, message = "Promo code cannot exceed 80 characters")
        String promoCode,

        @Size(max = 80, message = "Referral code cannot exceed 80 characters")
        String referralCode,

        @NotNull(message = "Terms acceptance is required")
        Boolean acceptedTerms,

        @NotBlank(message = "Consent version is required")
        @Size(max = 50, message = "Consent version cannot exceed 50 characters")
        String consentVersion,

        Boolean privateBooking
) {
}