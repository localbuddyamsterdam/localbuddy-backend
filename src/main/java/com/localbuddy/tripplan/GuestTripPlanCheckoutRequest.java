package com.localbuddy.tripplan;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Guest "book my trip" request: multi-selected itinerary item ids + guest identity/consent
 * (mirrors CreateGuestBookingRequest), applied to every booking in the bundle.
 */
public record GuestTripPlanCheckoutRequest(

        @NotEmpty(message = "Select at least one itinerary item to book")
        @Size(max = 12, message = "At most 12 items can be booked in one checkout")
        List<String> selectedItemIds,

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

        @NotNull(message = "Terms acceptance is required")
        Boolean acceptedTerms,

        @NotBlank(message = "Consent version is required")
        @Size(max = 50, message = "Consent version cannot exceed 50 characters")
        String consentVersion,

        @Size(max = 80, message = "Gift card code cannot exceed 80 characters")
        String giftCardCode,

        // Emergency contact (optional, same all-or-nothing rule as a single booking).
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

        /** Explicit consent to receive the bundle's booking updates on WhatsApp. */
        Boolean whatsAppOptIn
) {
}
