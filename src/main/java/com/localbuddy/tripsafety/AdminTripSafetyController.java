package com.localbuddy.tripsafety;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
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
}
