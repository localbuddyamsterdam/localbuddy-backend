package com.localbuddy.attraction;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated in-app attraction ticket ordering. Only live when
 * {@code app.attractions.booking-enabled} is on (distributor account approved); until then
 * travelers use each product's affiliate ticket link instead.
 */
@RestController
@RequestMapping("/api/attractions")
@Tag(name = "Attractions", description = "In-app attraction ticket orders (logged-in)")
@SecurityRequirement(name = "bearerAuth")
public class AttractionController {

    private final AttractionService attractionService;
    private final RateLimitService rateLimitService;

    public AttractionController(AttractionService attractionService, RateLimitService rateLimitService) {
        this.attractionService = attractionService;
        this.rateLimitService = rateLimitService;
    }

    @Operation(summary = "Order attraction tickets",
            description = "Places a ticket order with the provider for the logged-in traveler and records it. "
                    + "Tickets are issued to the account email.")
    @PostMapping("/orders")
    public ResponseEntity<AttractionOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateAttractionOrderRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        rateLimitService.checkPublicApiLimit("attractions-order:" + userId, 5, 60);
        return ResponseEntity.status(HttpStatus.CREATED).body(attractionService.createOrder(userId, request));
    }

    @Operation(summary = "List my attraction ticket orders", description = "Newest first.")
    @GetMapping("/orders/mine")
    public ResponseEntity<Page<AttractionOrderResponse>> myOrders(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ResponseEntity.ok(attractionService.listMine(userId, pageable));
    }
}
