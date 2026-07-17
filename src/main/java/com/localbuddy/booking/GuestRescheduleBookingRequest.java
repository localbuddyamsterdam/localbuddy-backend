package com.localbuddy.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Guest self-service reschedule, authorized by booking reference + the email on the booking. */
public record GuestRescheduleBookingRequest(

        @NotBlank(message = "Booking reference is required")
        @Size(max = 40, message = "Booking reference cannot exceed 40 characters")
        String bookingReference,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @NotNull(message = "New availability slot id is required")
        UUID newAvailabilitySlotId,

        @Size(max = 1000, message = "Reason cannot exceed 1000 characters")
        String reason
) {
}
