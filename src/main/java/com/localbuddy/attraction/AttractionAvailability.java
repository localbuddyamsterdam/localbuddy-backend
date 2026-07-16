package com.localbuddy.attraction;

import java.time.LocalDate;
import java.util.List;

/** Availability of one attraction product on one date, with entry timeslots when applicable. */
public record AttractionAvailability(
        LocalDate date,
        boolean available,
        /** Entry timeslots (empty for all-day tickets). */
        List<Timeslot> timeslots
) {

    /** One bookable entry window, e.g. 10:15. {@code id} is the provider's timeslot identifier. */
    public record Timeslot(String id, String startTime, boolean available) {
    }
}
