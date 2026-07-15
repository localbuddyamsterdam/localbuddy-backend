package com.localbuddy.tripplan;

/** An itinerary item that could not be booked during a bundle checkout, with the reason. */
public record TripPlanSkippedItem(
        String itemId,
        String title,
        String reason
) {
}
