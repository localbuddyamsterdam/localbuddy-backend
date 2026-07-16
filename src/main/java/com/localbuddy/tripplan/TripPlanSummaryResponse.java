package com.localbuddy.tripplan;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Lightweight row for "My itineraries" (`GET /api/trip-plans/mine`). Deliberately does not
 * carry the full {@link TripPlanDocument} or re-check live availability — that per-item work
 * only makes sense for the one plan someone is actually opening, not for every row in a list.
 */
public record TripPlanSummaryResponse(
        String token,
        String title,
        String citySlug,
        String cityName,
        LocalDate startDate,
        LocalDate endDate,
        Integer partySize,
        Instant createdAt
) {
}
