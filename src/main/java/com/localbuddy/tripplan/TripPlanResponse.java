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
        /** True when every EXPERIENCE item is a private whole-group buyout at a flat price. */
        boolean privateTour,
        String interests,
        /** Language of the itinerary text (en/nl/fr); also drives the PDF's static labels. */
        String language,
        TripPlanDocument plan,
        List<String> bookableItemIds,
        Instant createdAt
) {
}
