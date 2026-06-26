package com.localbuddy.payment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePaymentRequest(

        @NotNull(message = "Booking id is required")
        UUID bookingId,

        /** Optional gift card to apply as a payment method; covers up to its balance, Stripe charges the rest. */
        String giftCardCode
) {
}