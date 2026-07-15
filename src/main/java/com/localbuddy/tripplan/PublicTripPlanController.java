package com.localbuddy.tripplan;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicTripPlanController(
            TripPlanService tripPlanService,
            TripPlanCheckoutService tripPlanCheckoutService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.tripPlanService = tripPlanService;
        this.tripPlanCheckoutService = tripPlanCheckoutService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Generate a trip plan",
            description = "Builds a day-by-day itinerary for the given city and dates, weaving in real bookable "
                    + "LocalBuddy experiences at their actual slot times. Returns a saved, shareable plan.")
    @PostMapping
    public ResponseEntity<TripPlanResponse> createTripPlan(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateTripPlanRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        // Much tighter than the generic public limit — each call is a paid model generation.
        rateLimitService.checkPublicApiLimit("ai-trip-plan:" + clientIp, 3, 60);
        rateLimitService.checkPublicApiLimit("ai-trip-plan-daily:" + clientIp, 30, 86400);
        return ResponseEntity.status(HttpStatus.CREATED).body(tripPlanService.createPlan(request));
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
