package com.localbuddy.calendar;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings/{bookingId}")
@Tag(name = "Booking Calendar", description = "Add-to-calendar (.ics) export and deep links for a booking")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Operation(summary = "Download booking .ics",
            description = "Returns an iCalendar file for the booking (visible to its traveler and host).")
    @GetMapping(value = "/calendar.ics", produces = "text/calendar")
    public ResponseEntity<byte[]> downloadIcs(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        byte[] body = calendarService.buildIcs(userId, bookingId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"booking-" + bookingId + ".ics\"")
                .contentType(MediaType.parseMediaType("text/calendar"))
                .body(body);
    }

    @Operation(summary = "Get add-to-calendar links",
            description = "Returns Google/Outlook add-to-calendar deep links plus the .ics path.")
    @GetMapping("/calendar-links")
    public ResponseEntity<CalendarLinksResponse> calendarLinks(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(calendarService.buildLinks(userId, bookingId));
    }
}
