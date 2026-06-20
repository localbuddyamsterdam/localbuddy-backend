package com.localbuddy.tripsafety;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/trip-safety")
@Tag(name = "Trip Safety", description = "Emergency contact, trip check-in/out, and SOS during a live experience")
public class TripSafetyController {

    private final TripSafetyService tripSafetyService;

    public TripSafetyController(TripSafetyService tripSafetyService) {
        this.tripSafetyService = tripSafetyService;
    }

    @Operation(summary = "Get my emergency contact")
    @GetMapping("/emergency-contact")
    public ResponseEntity<EmergencyContactResponse> getMyEmergencyContact(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        EmergencyContactResponse contact = tripSafetyService.getMyEmergencyContact(userId);
        return contact == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(contact);
    }

    @Operation(summary = "Set/update my emergency contact")
    @PutMapping("/emergency-contact")
    public ResponseEntity<EmergencyContactResponse> upsertEmergencyContact(
            Authentication authentication,
            @Valid @RequestBody UpsertEmergencyContactRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(tripSafetyService.upsertEmergencyContact(userId, request));
    }

    @Operation(summary = "Check in to a trip")
    @PostMapping("/bookings/{bookingId}/check-in")
    public ResponseEntity<TripSafetyEventResponse> checkIn(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) TripCheckRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(tripSafetyService.checkIn(userId, bookingId, request));
    }

    @Operation(summary = "Check out of a trip")
    @PostMapping("/bookings/{bookingId}/check-out")
    public ResponseEntity<TripSafetyEventResponse> checkOut(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) TripCheckRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(tripSafetyService.checkOut(userId, bookingId, request));
    }

    @Operation(summary = "Raise an SOS", description = "Records an SOS event and alerts support with location and emergency contact.")
    @PostMapping("/bookings/{bookingId}/sos")
    public ResponseEntity<TripSafetyEventResponse> raiseSos(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) SosRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(tripSafetyService.raiseSos(userId, bookingId, request));
    }

    @Operation(summary = "List safety events for a booking", description = "Visible to the booking's traveler and host.")
    @GetMapping("/bookings/{bookingId}/events")
    public ResponseEntity<List<TripSafetyEventResponse>> getBookingEvents(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(tripSafetyService.getBookingEvents(userId, bookingId));
    }
}
