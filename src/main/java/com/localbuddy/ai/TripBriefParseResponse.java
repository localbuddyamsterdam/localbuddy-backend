package com.localbuddy.ai;

import java.time.LocalDate;

/**
 * Trip parameters lifted from a free-text ask — every field nullable; the planner form
 * only fills what came back and asks for the rest.
 */
public record TripBriefParseResponse(
        String citySlug,
        LocalDate startDate,
        LocalDate endDate,
        Integer budget,
        Integer partySize
) {
    public static TripBriefParseResponse empty() {
        return new TripBriefParseResponse(null, null, null, null, null);
    }
}
