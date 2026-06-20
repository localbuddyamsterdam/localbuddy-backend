package com.localbuddy.waitlist;

import jakarta.validation.constraints.*;

public record JoinGuestWaitlistRequest(

        @NotNull(message = "Guests count is required")
        @Min(value = 1, message = "Guests count must be at least 1")
        @Max(value = 10, message = "Guests count cannot exceed 10")
        Integer guestsCount,

        @NotBlank(message = "Guest name is required")
        @Size(max = 150, message = "Guest name cannot exceed 150 characters")
        String guestName,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @NotBlank(message = "Guest phone is required")
        @Size(max = 40, message = "Guest phone cannot exceed 40 characters")
        String guestPhone
) {
}
