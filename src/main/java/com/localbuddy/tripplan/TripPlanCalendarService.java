package com.localbuddy.tripplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.common.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a saved trip plan as an iCalendar (.ics) file — one VEVENT per scheduled item, so
 * the whole itinerary drops into Google/Apple/Outlook calendars in one import. Booked
 * experiences use their real slot start/end; FOOD/SIGHT suggestions use the plan's local time
 * with a default 75-minute duration; TIP items (advice, not appointments) are skipped.
 *
 * <p>Deliberately self-contained (own repository access) so it adds no constructor churn to
 * the already-large TripPlanService.
 */
@Service
public class TripPlanCalendarService {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final int SUGGESTION_MINUTES = 75;

    private final TripPlanRepository tripPlanRepository;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;

    public TripPlanCalendarService(TripPlanRepository tripPlanRepository,
                                   ObjectMapper objectMapper,
                                   @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.tripPlanRepository = tripPlanRepository;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    /** The rendered calendar plus its human-friendly download name. */
    public record CalendarFile(String filename, String content) {
    }

    @Transactional(readOnly = true)
    public CalendarFile buildCalendar(String token) {
        TripPlan plan = tripPlanRepository.findByToken(token)
                .filter(p -> p.getStatus() == TripPlanStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Trip plan not found"));

        TripPlanDocument doc;
        try {
            doc = objectMapper.readValue(plan.getPlan(), TripPlanDocument.class);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("This trip plan could not be loaded");
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(plan.getCity().getTimezone());
        } catch (Exception ex) {
            zone = ZoneId.of("Europe/Amsterdam");
        }

        String shareUrl = frontendBaseUrl + "/trip-planner/" + plan.getToken();
        Instant stamp = plan.getCreatedAt() != null ? plan.getCreatedAt() : Instant.now();

        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//LocalBuddy//Trip Genie//EN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:" + esc(doc.title()));

        for (TripPlanDay day : doc.days()) {
            for (TripPlanItem item : day.items()) {
                if ("TIP".equals(item.kind())) {
                    continue; // advice, not an appointment
                }
                Instant start;
                Instant end;
                if (item.bookable() && item.slotStartTime() != null) {
                    start = item.slotStartTime();
                    end = item.slotEndTime() != null
                            ? item.slotEndTime()
                            : start.plusSeconds(SUGGESTION_MINUTES * 60L);
                } else if (item.startTimeLocal() != null && !item.startTimeLocal().isBlank()) {
                    LocalTime local;
                    try {
                        local = LocalTime.parse(item.startTimeLocal());
                    } catch (Exception ex) {
                        continue; // unparseable time — leave it out of the calendar
                    }
                    start = day.date().atTime(local).atZone(zone).toInstant();
                    end = start.plusSeconds(SUGGESTION_MINUTES * 60L);
                } else {
                    continue;
                }

                String url = item.bookable() && item.bookingUrl() != null ? item.bookingUrl()
                        : item.mapsUrl() != null ? item.mapsUrl()
                        : shareUrl;
                String description = (item.description() == null ? "" : item.description())
                        + "\n\nYour itinerary: " + shareUrl;

                line(ics, "BEGIN:VEVENT");
                line(ics, "UID:" + plan.getToken() + "-" + item.id() + "@localbuddy");
                line(ics, "DTSTAMP:" + ICS_UTC.format(stamp));
                line(ics, "DTSTART:" + ICS_UTC.format(start));
                line(ics, "DTEND:" + ICS_UTC.format(end));
                line(ics, "SUMMARY:" + esc(item.title()));
                line(ics, "DESCRIPTION:" + esc(description));
                if (item.placeName() != null && !item.placeName().isBlank()) {
                    line(ics, "LOCATION:" + esc(item.placeName()));
                }
                line(ics, "URL:" + esc(url));
                line(ics, "END:VEVENT");
            }
        }

        line(ics, "END:VCALENDAR");

        String filename = "localbuddy-itinerary-" + plan.getCity().getSlug() + "-" + plan.getStartDate()
                + (plan.getEndDate().equals(plan.getStartDate()) ? "" : "-to-" + plan.getEndDate()) + ".ics";
        return new CalendarFile(filename, ics.toString());
    }

    /** Escapes text per RFC 5545 (backslash, semicolon, comma, newline). */
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** Appends a content line with RFC 5545 folding (75-octet limit, CRLF + space continuation). */
    private static void line(StringBuilder out, String content) {
        List<String> chunks = new java.util.ArrayList<>();
        String rest = content;
        int max = 74;
        while (rest.length() > max) {
            chunks.add(rest.substring(0, max));
            rest = rest.substring(max);
            max = 73; // continuation lines start with a space, so one char less
        }
        chunks.add(rest);
        out.append(String.join("\r\n ", chunks)).append("\r\n");
    }
}
