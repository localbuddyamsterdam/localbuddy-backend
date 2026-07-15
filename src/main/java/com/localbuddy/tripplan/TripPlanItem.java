package com.localbuddy.tripplan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of an itinerary day. EXPERIENCE items reference a real LocalBuddy experience;
 * when {@code bookable} they also carry the concrete slot chosen for that day plus a deep
 * link that starts checkout pre-filled. FOOD / SIGHT items are local suggestions with a
 * maps link; TIP items are practical advice.
 *
 * <p>{@code available} is null in the stored document and recomputed against live
 * availability on every read of the plan.
 */
public record TripPlanItem(
        String id,
        String startTimeLocal,
        String kind,
        String title,
        String description,
        UUID experienceId,
        String experienceSlug,
        String experienceTitle,
        String experienceUrl,
        String bookingUrl,
        UUID slotId,
        Instant slotStartTime,
        Instant slotEndTime,
        BigDecimal pricePerGuest,
        String placeName,
        String mapsUrl,
        boolean bookable,
        Boolean available
) {

    public TripPlanItem withAvailable(Boolean availableFlag) {
        return new TripPlanItem(id, startTimeLocal, kind, title, description, experienceId,
                experienceSlug, experienceTitle, experienceUrl, bookingUrl, slotId, slotStartTime,
                slotEndTime, pricePerGuest, placeName, mapsUrl, bookable, availableFlag);
    }
}
