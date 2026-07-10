package com.localbuddy.booking;

import jakarta.validation.constraints.*;

import java.util.UUID;

/**
 * Admin "create booking on behalf" — a manual booking confirmed immediately with
 * no online payment (collected offline). Blocks seats and supports age bands.
 */
public record AdminCreateBookingRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId,

        @NotNull(message = "Availability slot id is required")
        UUID availabilitySlotId,

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

        Boolean privateBooking,

        @Size(max = 1000, message = "Note cannot exceed 1000 characters")
        String note
) {
}
