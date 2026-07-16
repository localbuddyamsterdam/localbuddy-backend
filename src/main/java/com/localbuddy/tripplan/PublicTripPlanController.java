package com.localbuddy.tripplan;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/trip-plans")
@Tag(name = "AI Trip Planner", description = "AI-generated, bookable itineraries grounded in real availability")
public class PublicTripPlanController {

    private final TripPlanService tripPlanService;
    private final TripPlanCheckoutService tripPlanCheckoutService;
    private final TripPlanPdfService tripPlanPdfService;
    private final TripPlanCalendarService tripPlanCalendarService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicTripPlanController(
            TripPlanService tripPlanService,
            TripPlanCheckoutService tripPlanCheckoutService,
            TripPlanPdfService tripPlanPdfService,
            TripPlanCalendarService tripPlanCalendarService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.tripPlanService = tripPlanService;
        this.tripPlanCheckoutService = tripPlanCheckoutService;
        this.tripPlanPdfService = tripPlanPdfService;
        this.tripPlanCalendarService = tripPlanCalendarService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Get a saved trip plan",
            description = "The saved plan by its share token, with each bookable item's availability re-checked live.")
    @GetMapping("/{token}")
    public ResponseEntity<TripPlanResponse> getTripPlan(
            HttpServletRequest servletRequest,
            @PathVariable String token
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("trip-plan-view:" + clientIp);
        return ResponseEntity.ok(tripPlanService.getPlanByToken(token));
    }

    @Operation(summary = "Export a trip plan as PDF",
            description = "The saved plan, rendered as a downloadable day-by-day PDF itinerary.")
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            HttpServletRequest servletRequest,
            @PathVariable String token
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("trip-plan-pdf:" + clientIp, 10, 60);
        TripPlanResponse plan = tripPlanService.getPlanByToken(token);
        byte[] pdf = tripPlanPdfService.render(plan);
        // Human-friendly download name (city slug + ISO dates are already filename-safe),
        // e.g. localbuddy-itinerary-amsterdam-2026-07-20-to-2026-07-22.pdf
        String filename = "localbuddy-itinerary-" + plan.citySlug() + "-" + plan.startDate()
                + (plan.endDate().equals(plan.startDate()) ? "" : "-to-" + plan.endDate()) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @Operation(summary = "Export a trip plan as a calendar (.ics)",
            description = "The whole itinerary as one iCalendar file — booked experiences at their real slot "
                    + "times, suggestions at their planned local times — importable into Google/Apple/Outlook.")
    @GetMapping("/{token}/calendar")
    public ResponseEntity<byte[]> downloadCalendar(
            HttpServletRequest servletRequest,
            @PathVariable String token
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("trip-plan-calendar:" + clientIp, 10, 60);
        TripPlanCalendarService.CalendarFile calendar = tripPlanCalendarService.buildCalendar(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + calendar.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .body(calendar.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Operation(summary = "Rate a trip plan",
            description = "One-tap 'was this itinerary helpful?' feedback; a repeat vote overwrites the previous one.")
    @PostMapping("/{token}/feedback")
    public ResponseEntity<Void> submitFeedback(
            HttpServletRequest servletRequest,
            @PathVariable String token,
            @Valid @RequestBody TripPlanFeedbackRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("trip-plan-feedback:" + clientIp, 10, 60);
        tripPlanService.recordFeedback(token, request.helpful());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Book selected trip-plan items as a guest",
            description = "Creates one booking per selected itinerary item and ONE payment covering all of them. "
                    + "Items that can no longer be booked are skipped and reported.")
    @PostMapping("/{token}/checkout")
    public ResponseEntity<TripPlanCheckoutResponse> guestCheckout(
            HttpServletRequest servletRequest,
            @PathVariable String token,
            @Valid @RequestBody GuestTripPlanCheckoutRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        // Tighter than the generic limit: each call can create up to 12 seat-holding bookings.
        rateLimitService.checkPublicApiLimit("trip-plan-checkout:" + clientIp, 5, 60);
        TripPlanCheckoutResponse response = tripPlanCheckoutService.checkoutAsGuest(
                token, request, clientIp, servletRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
