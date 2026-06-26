package com.localbuddy.payment;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGuestPaymentRequest(

        @NotBlank(message = "Booking reference is required")
        @Size(max = 40, message = "Booking reference cannot exceed 40 characters")
        String bookingReference,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        /** Optional gift card to apply as a payment method; covers up to its balance, Stripe charges the rest. */
        String giftCardCode
) {
}