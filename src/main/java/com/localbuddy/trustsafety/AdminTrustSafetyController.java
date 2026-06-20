package com.localbuddy.trustsafety;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/trust-safety")
@Tag(name = "Admin Trust & Safety", description = "Admin endpoints for managing safety reports and user restrictions")
public class AdminTrustSafetyController {

    private final TrustSafetyService trustSafetyService;

    public AdminTrustSafetyController(TrustSafetyService trustSafetyService) {
        this.trustSafetyService = trustSafetyService;
    }

    @Operation(
            summary = "List safety reports",
            description = "Returns safety reports, optionally filtered by status. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety reports retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/reports")
    public ResponseEntity<List<SafetyReportResponse>> getReports(
            @RequestParam(required = false) SafetyReportStatus status
    ) {
        return ResponseEntity.ok(trustSafetyService.getAdminReports(status));
    }

    @Operation(
            summary = "Update a safety report",
            description = "Updates the status or details of an existing safety report. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety report updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Safety report not found")
    })
    @PutMapping("/reports/{reportId}")
    public ResponseEntity<SafetyReportResponse> updateReport(
            @PathVariable UUID reportId,
            @Valid @RequestBody UpdateSafetyReportRequest request
    ) {
        return ResponseEntity.ok(trustSafetyService.updateReport(reportId, request));
    }

    @Operation(
            summary = "List active user restrictions",
            description = "Returns all currently active user restrictions. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active restrictions retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/restrictions")
    public ResponseEntity<List<UserRestrictionResponse>> getActiveRestrictions() {
        return ResponseEntity.ok(trustSafetyService.getActiveRestrictions());
    }

    @Operation(
            summary = "Create a user restriction",
            description = "Creates a new restriction against a user. The acting admin is taken from the authenticated user. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User restriction created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @PostMapping("/restrictions")
    public ResponseEntity<UserRestrictionResponse> createRestriction(
            Authentication authentication,
            @Valid @RequestBody CreateUserRestrictionRequest request
    ) {
        UUID adminUserId = UUID.fromString(authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(trustSafetyService.createRestriction(adminUserId, request));
    }

    @Operation(
            summary = "Deactivate a user restriction",
            description = "Deactivates an existing user restriction by its ID. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User restriction deactivated successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "User restriction not found")
    })
    @PostMapping("/restrictions/{restrictionId}/deactivate")
    public ResponseEntity<UserRestrictionResponse> deactivateRestriction(
            @PathVariable UUID restrictionId
    ) {
        return ResponseEntity.ok(trustSafetyService.deactivateRestriction(restrictionId));
    }
}