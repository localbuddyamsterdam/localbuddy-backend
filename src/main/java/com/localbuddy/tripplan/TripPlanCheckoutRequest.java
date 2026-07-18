package com.localbuddy.tripplan;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Logged-in "book my trip" request: the multi-selected itinerary item ids plus the party
 * composition applied to every booking (the same group attends everything). One optional
 * gift card covers the whole bundle.
 */
public record TripPlanCheckoutRequest(

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

        @Size(max = 80, message = "Gift card code cannot exceed 80 characters")
        String giftCardCode,

        /**
         * Stackable promo/voucher codes for the bundle. Each is applied per booking, and only
         * to the bookings it validates for (a code scoped to one experience discounts just
         * that item); a code valid for nothing in the selection rejects the checkout.
         */
        @Size(max = 5, message = "At most 5 promo codes can be applied")
        List<@Size(max = 80, message = "Promo code cannot exceed 80 characters") String> promoCodes,

        /** One referral code, applied to a single booking of the bundle (the priciest). */
        @Size(max = 80, message = "Referral code cannot exceed 80 characters")
        String referralCode,

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
