package com.localbuddy.tripplan;

import java.util.List;

/**
 * The swap dialog's payload: up to a handful of alternatives for one itinerary item.
 * {@code aiRefined} is true when the traveler's free-text instruction was applied by
 * the model (so the UI can say "refined for you" honestly — a blank instruction or an
 * unavailable model falls back to the default ranking).
 */
public record TripPlanSwapOptionsResponse(List<TripPlanSwapOption> options, boolean aiRefined) {
}
