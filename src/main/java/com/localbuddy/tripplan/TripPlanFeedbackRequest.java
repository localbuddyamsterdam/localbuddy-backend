package com.localbuddy.tripplan;

import jakarta.validation.constraints.NotNull;

/** One-tap plan feedback: was this itinerary helpful? */
public record TripPlanFeedbackRequest(
        @NotNull(message = "helpful is required")
        Boolean helpful
) {
}
