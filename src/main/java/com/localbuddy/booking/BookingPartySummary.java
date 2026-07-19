package com.localbuddy.booking;

import java.util.ArrayList;
import java.util.List;

/** Renders a booking's age-band breakdown for host-facing messages, e.g. "2 adults, 1 child". */
public final class BookingPartySummary {

    private BookingPartySummary() {
    }

    public static String describe(Booking booking) {
        List<String> parts = new ArrayList<>();
        addBand(parts, booking.getAdultsCount(), "adult", "adults");
        addBand(parts, booking.getTeensCount(), "teen", "teens");
        addBand(parts, booking.getChildrenCount(), "child", "children");
        addBand(parts, booking.getInfantsCount(), "infant", "infants");
        if (!parts.isEmpty()) {
            return String.join(", ", parts);
        }
        int total = booking.getGuestsCount() != null ? booking.getGuestsCount() : 1;
        return total + (total == 1 ? " guest" : " guests");
    }

    private static void addBand(List<String> parts, Integer count, String singular, String plural) {
        if (count != null && count > 0) {
            parts.add(count + " " + (count == 1 ? singular : plural));
        }
    }
}
