package com.localbuddy.tripplan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One candidate the swap dialog offers in place of an itinerary item: a different
 * experience on the same day, at its slot closest to the item's current time.
 * {@code price} is per guest on shared plans and the flat whole-group buyout on
 * private plans (same semantics as {@link TripPlanItem#pricePerGuest()}).
 */
public record TripPlanSwapOption(
        UUID slotId,
        UUID experienceId,
        String experienceSlug,
        String title,
        String shortDescription,
        String categoryName,
        String startTimeLocal,
        Integer durationMinutes,
        BigDecimal price,
        String currency,
        BigDecimal hostRating,
        Integer hostReviewCount,
        String imageUrl,
        String meetingArea
) {
}
