package com.localbuddy.tripplan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A saved AI itinerary as served publicly: the validated plan document plus share/booking
 * links. Low-inventory situations are explained in the plan's summary text; items skipped
 * during a bundle checkout are reported via TripPlanCheckoutResponse.skippedItems.
 */
public record TripPlanResponse(
        String token,
        String shareUrl,
        String bookAllUrl,
        String citySlug,
        String cityName,
        String country,
        LocalDate startDate,
        LocalDate endDate,
        Integer partySize,
        String interests,
        TripPlanDocument plan,
        List<String> bookableItemIds,
        Instant createdAt
) {
}
