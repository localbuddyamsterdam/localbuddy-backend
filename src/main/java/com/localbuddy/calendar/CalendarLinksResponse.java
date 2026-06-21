package com.localbuddy.calendar;

/** "Add to calendar" deep links for a booking. */
public record CalendarLinksResponse(
        String googleLink,
        String outlookLink,
        String icsPath
) {
}
