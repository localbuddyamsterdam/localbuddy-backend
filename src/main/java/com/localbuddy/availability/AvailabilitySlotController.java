package com.localbuddy.availability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/availability")
@Tag(name = "Availability", description = "Endpoints for managing a local's availability slots")
@SecurityRequirement(name = "bearerAuth")
public class AvailabilitySlotController {

    private final AvailabilitySlotService availabilitySlotService;

    public AvailabilitySlotController(AvailabilitySlotService availabilitySlotService) {
        this.availabilitySlotService = availabilitySlotService;
    }

    @Operation(
            summary = "Create my availability slot",
            description = "Creates a new availability slot for the currently authenticated user (local)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Availability slot created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<AvailabilitySlotResponse> createMyAvailabilitySlot(
            Authentication authentication,
            @Valid @RequestBody CreateAvailabilitySlotRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        AvailabilitySlotResponse response = availabilitySlotService.createMyAvailabilitySlot(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Generate availability slots from a weekly schedule",
            description = "Bulk-creates slots from weekly rules (e.g. Mon + Sat at 10:00 and 14:00) over a date "
                    + "range (max 120 days, max 300 slots per call). Times are wall-clock in the given timezone "
                    + "(default Europe/Amsterdam). Existing slots at the same start time and past times are skipped."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Slots generated (see created/skipped counts)"),
            @ApiResponse(responseCode = "400", description = "Invalid schedule, range too large, or too many slots"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/generate")
    public ResponseEntity<GenerateAvailabilityResponse> generateMyAvailabilitySlots(
            Authentication authentication,
            @Valid @RequestBody GenerateAvailabilityRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        GenerateAvailabilityResponse response = availabilitySlotService.generateMyAvailabilitySlots(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get my availability slots",
            description = "Returns all availability slots belonging to the currently authenticated user (local)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Availability slots retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<List<AvailabilitySlotResponse>> getMyAvailabilitySlots(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(availabilitySlotService.getMyAvailabilitySlots(userId));
    }

    @Operation(
            summary = "Block one of my availability slots",
            description = "Blocks a slot so no new bookings can be made. Rejected if the slot still has "
                    + "active bookings — cancel those first (only possible more than 24h before start)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slot blocked"),
            @ApiResponse(responseCode = "400", description = "Slot has active bookings"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Slot not found")
    })
    @PostMapping("/{slotId}/block")
    public ResponseEntity<AvailabilitySlotResponse> blockMyAvailabilitySlot(
            Authentication authentication,
            @PathVariable UUID slotId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(availabilitySlotService.blockMyAvailabilitySlot(userId, slotId));
    }

    @Operation(summary = "Unblock one of my availability slots",
            description = "Re-opens a previously blocked slot for bookings.")
    @PostMapping("/{slotId}/unblock")
    public ResponseEntity<AvailabilitySlotResponse> unblockMyAvailabilitySlot(
            Authentication authentication,
            @PathVariable UUID slotId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(availabilitySlotService.unblockMyAvailabilitySlot(userId, slotId));
    }

    @Operation(
            summary = "Delete one of my availability slots",
            description = "Removes a slot. Rejected if the slot still has active bookings."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Slot deleted"),
            @ApiResponse(responseCode = "400", description = "Slot has active bookings"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Slot not found")
    })
    @DeleteMapping("/{slotId}")
    public ResponseEntity<Void> deleteMyAvailabilitySlot(
            Authentication authentication,
            @PathVariable UUID slotId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        availabilitySlotService.deleteMyAvailabilitySlot(userId, slotId);
        return ResponseEntity.noContent().build();
    }
}