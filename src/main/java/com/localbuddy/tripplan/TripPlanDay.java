package com.localbuddy.tripplan;

import java.time.LocalDate;
import java.util.List;

/** One itinerary day: a date, a short theme line, and time-ordered items. */
public record TripPlanDay(
        LocalDate date,
        String theme,
        List<TripPlanItem> items
) {

    public TripPlanDay withItems(List<TripPlanItem> newItems) {
        return new TripPlanDay(date, theme, newItems);
    }
}
