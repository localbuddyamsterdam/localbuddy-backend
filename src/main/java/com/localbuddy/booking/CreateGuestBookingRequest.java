package com.localbuddy.booking;

import jakarta.validation.constraints.*;

import java.util.List;
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

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String guestFirstName,

        @NotBlank(message = "Last name is required")
        @Size(min = 2, max = 100, message = "Last name must be between 2 and 100 characters")
        String guestLastName,

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

        /** Additional stacked promo/voucher codes; all applied codes must be combinable. */
        List<String> promoCodes,

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