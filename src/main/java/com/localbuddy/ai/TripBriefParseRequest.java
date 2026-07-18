package com.localbuddy.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A traveler's free-text Trip Genie ask, to be lifted into planner form fields. */
public record TripBriefParseRequest(
        @NotBlank(message = "Text is required")
        @Size(max = 600, message = "Text cannot exceed 600 characters")
        String text
) {
}
