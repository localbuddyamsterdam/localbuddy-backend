package com.localbuddy.calendar;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Builds an iCalendar (.ics) document and "add to calendar" links for a booking. */
@Service
public class CalendarService {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final CalendarBookingRepository bookingRepository;

    public CalendarService(CalendarBookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public String buildIcs(UUID userId, UUID bookingId) {
        Booking booking = requireParticipant(userId, bookingId);

        Instant start = slotStart(booking);
        Instant end = slotEnd(booking);
        String title = experienceTitle(booking);
        String location = location(booking);
        String description = "LocalBuddy booking " + booking.getBookingReference();

        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//LocalBuddy//Booking//EN");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + booking.getId() + "@localbuddy");
        line(sb, "DTSTAMP:" + ICS_UTC.format(Instant.now()));
        line(sb, "DTSTART:" + ICS_UTC.format(start));
        line(sb, "DTEND:" + ICS_UTC.format(end));
        line(sb, "SUMMARY:" + escape(title));
        line(sb, "DESCRIPTION:" + escape(description));
        if (location != null) {
            line(sb, "LOCATION:" + escape(location));
        }
        line(sb, "STATUS:CONFIRMED");
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public CalendarLinksResponse buildLinks(UUID userId, UUID bookingId) {
        Booking booking = requireParticipant(userId, bookingId);

        Instant start = slotStart(booking);
        Instant end = slotEnd(booking);
        String title = experienceTitle(booking);
        String location = location(booking);
        String details = "LocalBuddy booking " + booking.getBookingReference();

        String google = "https://calendar.google.com/calendar/render?action=TEMPLATE"
                + "&text=" + enc(title)
                + "&dates=" + ICS_UTC.format(start) + "/" + ICS_UTC.format(end)
                + "&details=" + enc(details)
                + (location != null ? "&location=" + enc(location) : "");

        String outlook = "https://outlook.live.com/calendar/0/deeplink/compose?path=/calendar/action/compose"
                + "&rru=addevent"
                + "&subject=" + enc(title)
                + "&startdt=" + enc(start.toString())
                + "&enddt=" + enc(end.toString())
                + "&body=" + enc(details)
                + (location != null ? "&location=" + enc(location) : "");

        String icsPath = "/api/bookings/" + bookingId + "/calendar.ics";
        return new CalendarLinksResponse(google, outlook, icsPath);
    }

    /** Public Google "add to calendar" link for a booking (no auth) — used in confirmation messages. */
    public String googleCalendarLink(Booking booking) {
        Instant start = slotStart(booking);
        Instant end = slotEnd(booking);
        String title = experienceTitle(booking);
        String location = location(booking);
        String details = "LocalBuddy booking " + booking.getBookingReference();
        return "https://calendar.google.com/calendar/render?action=TEMPLATE"
                + "&text=" + enc(title)
                + "&dates=" + ICS_UTC.format(start) + "/" + ICS_UTC.format(end)
                + "&details=" + enc(details)
                + (location != null ? "&location=" + enc(location) : "");
    }

    private Booking requireParticipant(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        boolean isTraveler = booking.getLoggedInUser() != null
                && booking.getLoggedInUser().getId().equals(userId);
        boolean isHost = booking.getLocalProfile() != null
                && booking.getLocalProfile().getUser() != null
                && booking.getLocalProfile().getUser().getId().equals(userId);
        if (!isTraveler && !isHost) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    private Instant slotStart(Booking booking) {
        if (booking.getAvailabilitySlot() == null || booking.getAvailabilitySlot().getStartTime() == null) {
            throw new BadRequestException("Booking has no scheduled time");
        }
        return booking.getAvailabilitySlot().getStartTime();
    }

    private Instant slotEnd(Booking booking) {
        Instant end = booking.getAvailabilitySlot() != null ? booking.getAvailabilitySlot().getEndTime() : null;
        return end != null ? end : slotStart(booking).plusSeconds(3600);
    }

    private String experienceTitle(Booking booking) {
        Experience experience = booking.getExperience();
        return experience != null && experience.getTitle() != null ? experience.getTitle() : "LocalBuddy experience";
    }

    private String location(Booking booking) {
        Experience experience = booking.getExperience();
        if (experience == null) {
            return null;
        }
        if (experience.getMeetingArea() != null && !experience.getMeetingArea().isBlank()) {
            return experience.getMeetingArea();
        }
        return experience.getCity() != null ? experience.getCity().getName() : null;
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Escapes a text value per RFC 5545 (backslash, comma, semicolon, newline). */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }

    private void line(StringBuilder sb, String content) {
        sb.append(content).append("\r\n");
    }
}
