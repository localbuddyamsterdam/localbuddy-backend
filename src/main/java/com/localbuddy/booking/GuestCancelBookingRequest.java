package com.localbuddy.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Guest self-service cancellation, authorized by booking reference + the email on the booking. */
public record GuestCancelBookingRequest(

        @NotBlank(message = "Booking reference is required")
        @Size(max = 40, message = "Booking reference cannot exceed 40 characters")
        String bookingReference,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @Size(max = 1000, message = "Cancellation reason cannot exceed 1000 characters")
        String reason
) {
}
