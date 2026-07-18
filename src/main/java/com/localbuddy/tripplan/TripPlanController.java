package com.localbuddy.tripplan;

import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Authenticated AI trip-plan generation and "My itineraries" listing. Generating an itinerary
 * requires login (unlike viewing a shared plan or booking as a guest, which stay public under
 * {@link PublicTripPlanController}) so every plan can be saved against an account and each
 * paid model call is tied to a known user.
 */
@RestController
@RequestMapping("/api/trip-plans")
@Tag(name = "AI Trip Planner", description = "Logged-in itinerary generation and saved-plan listing")
@SecurityRequirement(name = "bearerAuth")
public class TripPlanController {

    private final TripPlanService tripPlanService;
    private final RateLimitService rateLimitService;

    public TripPlanController(TripPlanService tripPlanService, RateLimitService rateLimitService) {
        this.tripPlanService = tripPlanService;
        this.rateLimitService = rateLimitService;
    }

    @Operation(summary = "Generate a trip plan",
            description = "Builds a day-by-day itinerary for the given city and dates, weaving in real bookable "
                    + "LocalBuddy experiences at their actual slot times. Saved against the logged-in traveler.")
    @PostMapping
    public ResponseEntity<TripPlanResponse> createTripPlan(
            Authentication authentication,
            @Valid @RequestBody CreateTripPlanRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        // Per-user, not per-IP: each call is a paid model generation.
        rateLimitService.checkPublicApiLimit("ai-trip-plan-user:" + userId, 3, 60);
        rateLimitService.checkPublicApiLimit("ai-trip-plan-user-daily:" + userId, 30, 86400);
        return ResponseEntity.status(HttpStatus.CREATED).body(tripPlanService.createPlan(request, userId));
    }

    @Operation(summary = "List my saved itineraries",
            description = "Lightweight rows (no live availability refresh) for the account 'My itineraries' tab, "
                    + "newest first.")
    @GetMapping("/mine")
    public ResponseEntity<Page<TripPlanSummaryResponse>> getMine(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ResponseEntity.ok(tripPlanService.listMine(userId, pageable));
    }

    @Operation(summary = "Replace a sold-out itinerary item",
            description = "Verifies the item's slot really is no longer bookable, then swaps in the closest live "
                    + "alternative for the same day — preferring another time of the same experience, otherwise "
                    + "an experience not already in the plan. Owner only.")
    @PostMapping("/{token}/items/{itemId}/alternative")
    public ResponseEntity<TripPlanResponse> replaceSoldOutItem(
            Authentication authentication,
            @PathVariable String token,
            @PathVariable String itemId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        rateLimitService.checkPublicApiLimit("trip-plan-heal:" + userId, 10, 60);
        return ResponseEntity.ok(tripPlanService.replaceSoldOutItem(userId, token, itemId));
    }

    @Operation(summary = "List swap options for an itinerary item",
            description = "Up to 4 DIFFERENT experiences bookable on the item's day (each at its slot closest "
                    + "to the item's time) for the swap dialog. An optional free-text instruction AI-ranks the "
                    + "options to the traveler's wish. Owner only.")
    @PostMapping("/{token}/items/{itemId}/swap-options")
    public ResponseEntity<TripPlanSwapOptionsResponse> swapOptions(
            Authentication authentication,
            @PathVariable String token,
            @PathVariable String itemId,
            @Valid @RequestBody(required = false) SwapOptionsRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        rateLimitService.checkPublicApiLimit("trip-plan-swap-options:" + userId, 20, 60);
        String instruction = request != null ? request.instruction() : null;
        if (instruction != null && !instruction.isBlank()) {
            // Refining with an instruction is a paid model call — throttle it separately.
            rateLimitService.checkPublicApiLimit("trip-plan-swap-ai:" + userId, 6, 60);
            rateLimitService.checkPublicApiLimit("trip-plan-swap-ai-daily:" + userId, 60, 86400);
        }
        return ResponseEntity.ok(tripPlanService.swapOptions(userId, token, itemId, instruction));
    }

    @Operation(summary = "Swap an itinerary item",
            description = "Deliberately replaces a bookable item with a DIFFERENT experience on the same day. "
                    + "The optional body carries the slot picked in the swap dialog; without it the closest-time "
                    + "candidate is auto-picked. Owner only.")
    @PostMapping("/{token}/items/{itemId}/swap")
    public ResponseEntity<TripPlanResponse> swapItem(
            Authentication authentication,
            @PathVariable String token,
            @PathVariable String itemId,
            @RequestBody(required = false) SwapItemRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        rateLimitService.checkPublicApiLimit("trip-plan-swap:" + userId, 10, 60);
        return ResponseEntity.ok(tripPlanService.swapItem(userId, token, itemId,
                request != null ? request.slotId() : null));
    }

    @Operation(summary = "Refine one itinerary day",
            description = "Regenerates a single day of the plan to the owner's free-text instruction "
                    + "(\"more food, slower morning\") — one day-scoped model call, validated against live "
                    + "inventory exactly like initial generation. Owner only.")
    @PostMapping("/{token}/days/{date}/refine")
    public ResponseEntity<TripPlanResponse> refineDay(
            Authentication authentication,
            @PathVariable String token,
            @PathVariable LocalDate date,
            @Valid @RequestBody RefineDayRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        // Per-user, not per-IP: each refine is a paid model call.
        rateLimitService.checkPublicApiLimit("trip-plan-refine:" + userId, 4, 60);
        rateLimitService.checkPublicApiLimit("trip-plan-refine-daily:" + userId, 40, 86400);
        return ResponseEntity.ok(tripPlanService.refineDay(userId, token, date, request.instruction()));
    }
}
