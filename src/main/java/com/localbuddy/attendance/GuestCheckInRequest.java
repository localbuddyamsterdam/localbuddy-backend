package com.localbuddy.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Anonymous-guest check-in: identified by booking reference + email (same pattern as guest booking
 * lookup), with the device coordinates and accuracy.
 */
public record GuestCheckInRequest(
        @NotBlank(message = "Booking reference is required")
        @Size(max = 40, message = "Booking reference cannot exceed 40 characters")
        String bookingReference,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude out of range")
        @DecimalMax(value = "90.0", message = "Latitude out of range")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude out of range")
        @DecimalMax(value = "180.0", message = "Longitude out of range")
        BigDecimal longitude,

        @PositiveOrZero(message = "Accuracy cannot be negative")
        Double accuracyMeters
) {
}
