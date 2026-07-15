package com.localbuddy.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI "what guests say" digest for an experience page. {@code available} is false when the
 * experience doesn't have enough visible reviews yet (or AI is not configured) — the page
 * then simply hides the section.
 */
public record ReviewSummaryResponse(
        UUID experienceId,
        boolean available,
        String summary,
        List<String> highlights,
        List<String> concerns,
        int reviewCount,
        BigDecimal averageRating,
        Instant generatedAt
) {

    public static ReviewSummaryResponse unavailable(UUID experienceId, int reviewCount) {
        return new ReviewSummaryResponse(experienceId, false, null, List.of(), List.of(),
                reviewCount, null, null);
    }
}
