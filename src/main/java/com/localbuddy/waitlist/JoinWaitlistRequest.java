package com.localbuddy.waitlist;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record JoinWaitlistRequest(

        @NotNull(message = "Guests count is required")
        @Min(value = 1, message = "Guests count must be at least 1")
        @Max(value = 10, message = "Guests count cannot exceed 10")
        Integer guestsCount
) {
}
