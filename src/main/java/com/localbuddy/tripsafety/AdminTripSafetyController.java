package com.localbuddy.tripsafety;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/trip-safety")
@Tag(name = "Admin - Trip Safety", description = "Admin monitoring and resolution of live-trip SOS events")
public class AdminTripSafetyController {

    private final TripSafetyService tripSafetyService;

    public AdminTripSafetyController(TripSafetyService tripSafetyService) {
        this.tripSafetyService = tripSafetyService;
    }

    @Operation(summary = "List open SOS events")
    @GetMapping("/sos")
    public ResponseEntity<List<TripSafetyEventResponse>> listOpenSos() {
        return ResponseEntity.ok(tripSafetyService.listOpenSos());
    }

    @Operation(summary = "Resolve an SOS event")
    @PostMapping("/sos/{eventId}/resolve")
    public ResponseEntity<TripSafetyEventResponse> resolveSos(@PathVariable UUID eventId) {
        return ResponseEntity.ok(tripSafetyService.resolveSos(eventId));
    }

    @Operation(summary = "Acknowledge an SOS event", description = "Marks that a responder has eyes on it.")
    @PostMapping("/sos/{eventId}/acknowledge")
    public ResponseEntity<TripSafetyEventResponse> acknowledgeSos(Authentication authentication, @PathVariable UUID eventId) {
        UUID adminId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(tripSafetyService.acknowledgeSos(eventId, adminId));
    }

    @Operation(summary = "Escalate an SOS event", description = "Bumps to on-call and re-fires the operator alert.")
    @PostMapping("/sos/{eventId}/escalate")
    public ResponseEntity<TripSafetyEventResponse> escalateSos(@PathVariable UUID eventId) {
        return ResponseEntity.ok(tripSafetyService.escalateSos(eventId));
    }

    @Operation(summary = "Count open SOS events", description = "For a live admin attention badge.")
    @GetMapping("/sos/count")
    public ResponseEntity<Long> openSosCount() {
        return ResponseEntity.ok(tripSafetyService.openSosCount());
    }
}
