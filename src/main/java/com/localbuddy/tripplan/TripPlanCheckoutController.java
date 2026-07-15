package com.localbuddy.tripplan;

import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/trip-plans")
@Tag(name = "AI Trip Planner", description = "Logged-in bundle checkout for a saved trip plan")
public class TripPlanCheckoutController {

    private final TripPlanCheckoutService tripPlanCheckoutService;
    private final RateLimitService rateLimitService;

    public TripPlanCheckoutController(
            TripPlanCheckoutService tripPlanCheckoutService,
            RateLimitService rateLimitService
    ) {
        this.tripPlanCheckoutService = tripPlanCheckoutService;
        this.rateLimitService = rateLimitService;
    }

    @Operation(summary = "Book selected trip-plan items",
            description = "Creates one booking per selected itinerary item for the logged-in traveler and ONE "
                    + "payment covering all of them. Items that can no longer be booked are skipped and reported.")
    @PostMapping("/{token}/checkout")
    public ResponseEntity<TripPlanCheckoutResponse> checkout(
            Authentication authentication,
            @PathVariable String token,
            @Valid @RequestBody TripPlanCheckoutRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        // Per-user limit: each call can create up to 12 seat-holding bookings.
        rateLimitService.checkPublicApiLimit("trip-plan-checkout-user:" + userId, 5, 60);
        TripPlanCheckoutResponse response = tripPlanCheckoutService.checkoutAsUser(userId, token, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
