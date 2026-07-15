package com.localbuddy.tripplan;

import java.math.BigDecimal;
import java.util.List;

/**
 * The enriched, validated itinerary exactly as stored on {@link TripPlan} and served to
 * the frontend. Every EXPERIENCE reference in it has been checked against real inventory.
 */
public record TripPlanDocument(
        String title,
        String summary,
        String currency,
        BigDecimal estimatedBookableTotal,
        List<TripPlanDay> days,
        List<String> tips
) {

    public TripPlanDocument withDays(List<TripPlanDay> newDays) {
        return new TripPlanDocument(title, summary, currency, estimatedBookableTotal, newDays, tips);
    }
}
