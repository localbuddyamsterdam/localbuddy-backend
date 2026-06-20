package com.localbuddy.safety;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings/{bookingId}/safety-checklist")
@Tag(name = "Booking Safety Checklist", description = "Endpoints for viewing and completing the safety checklist for a booking")
public class BookingSafetyChecklistController {

    private final BookingSafetyChecklistService checklistService;

    public BookingSafetyChecklistController(BookingSafetyChecklistService checklistService) {
        this.checklistService = checklistService;
    }

    @Operation(
            summary = "Get my safety checklist",
            description = "Returns the authenticated user's safety checklist for the given booking."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety checklist retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Booking or checklist not found")
    })
    @GetMapping
    public ResponseEntity<BookingSafetyChecklistResponse> getMyChecklist(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(checklistService.getMyChecklist(userId, bookingId));
    }

    @Operation(
            summary = "Complete my safety checklist",
            description = "Marks the authenticated user's safety checklist for the given booking as completed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety checklist completed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Booking or checklist not found")
    })
    @PostMapping("/complete")
    public ResponseEntity<BookingSafetyChecklistResponse> completeMyChecklist(
            Authentication authentication,
            @PathVariable UUID bookingId,
            HttpServletRequest servletRequest,
            @Valid @RequestBody CompleteBookingSafetyChecklistRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        return ResponseEntity.ok(
                checklistService.completeMyChecklist(
                        userId,
                        bookingId,
                        request,
                        resolveClientIp(servletRequest),
                        servletRequest.getHeader("User-Agent")
                )
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}