package com.localbuddy.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
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

        /** Additional stacked promo/voucher codes; all applied codes must be combinable. */
        List<String> promoCodes,

        @Size(max = 80, message = "Referral code cannot exceed 80 characters")
        String referralCode,

        /** Optional gift card applied as a payment method at checkout (covers up to its balance, Stripe charges the rest). */
        @Size(max = 80, message = "Gift card code cannot exceed 80 characters")
        String giftCardCode,

        Boolean privateBooking,

        // Emergency contact (optional). "All-or-nothing" is enforced in BookingService:
        // if any core field is present, first/last name + phone + relationship are required.
        @Size(max = 100, message = "Emergency contact first name cannot exceed 100 characters")
        String emergencyContactFirstName,

        @Size(max = 100, message = "Emergency contact last name cannot exceed 100 characters")
        String emergencyContactLastName,

        @Email(message = "Emergency contact email must be valid")
        @Size(max = 255, message = "Emergency contact email cannot exceed 255 characters")
        String emergencyContactEmail,

        @Size(max = 40, message = "Emergency contact phone cannot exceed 40 characters")
        String emergencyContactPhone,

        @Size(max = 80, message = "Emergency contact relationship cannot exceed 80 characters")
        String emergencyContactRelationship,

        /** Explicit consent to receive booking updates (confirmation/reminder/changes) on WhatsApp. */
        Boolean whatsAppOptIn
) {
}