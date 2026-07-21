package com.localbuddy.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Host-facing booking listing.
 * <p>
 * {@code /api/bookings/me} is deliberately traveller-scoped and must never widen, so hosts had no
 * way to enumerate the bookings on their own experiences — they could accept, decline, complete or
 * reschedule a booking, but only if they already knew its id. This is the missing read side.
 * <p>
 * Scope is taken from the authenticated principal only; there is no host/profile id parameter, so
 * one host can never request another's bookings.
 */
@RestController
@RequestMapping("/api/host/bookings")
@Tag(name = "Host Bookings", description = "Bookings received on the authenticated host's own experiences")
public class HostBookingController {

    private final BookingService bookingService;

    public HostBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "List my incoming bookings",
            description = "Returns bookings on the authenticated host's experiences, newest first. "
                    + "Optionally filtered to a single status (e.g. REQUESTED to find requests awaiting a decision). "
                    + "The traveller's emergency contact is withheld from host responses.")
    @GetMapping
    public ResponseEntity<List<BookingResponse>> getMyHostBookings(
            @RequestParam(required = false) BookingStatus status,
            Authentication authentication) {
        UUID hostUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.getHostBookings(hostUserId, status));
    }
}
