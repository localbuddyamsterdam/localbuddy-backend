package com.localbuddy.admin;

import com.localbuddy.safety.AdminSafetyReportDecisionRequest;
import com.localbuddy.safety.SafetyReportResponse;
import com.localbuddy.safety.SafetyReportService;
import com.localbuddy.safety.SafetyReportStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/safety/reports")
@Tag(name = "Admin - Safety Reports", description = "Admin endpoints for reviewing and resolving user-submitted safety reports")
public class AdminSafetyReportController {

    private final SafetyReportService safetyReportService;

    public AdminSafetyReportController(SafetyReportService safetyReportService) {
        this.safetyReportService = safetyReportService;
    }

    @Operation(
            summary = "List safety reports",
            description = "Admin only. Returns all safety reports, optionally filtered by status."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety reports retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    })
    @GetMapping
    public ResponseEntity<List<SafetyReportResponse>> getAdminReports(
            @Parameter(description = "Optional status filter for the returned reports")
            @RequestParam(required = false) SafetyReportStatus status
    ) {
        return ResponseEntity.ok(safetyReportService.getAdminReports(status));
    }

    @Operation(
            summary = "Mark a safety report as in review",
            description = "Admin only. Transitions the given safety report into the in-review state."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety report marked in review"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "404", description = "Safety report not found")
    })
    @PostMapping("/{reportId}/mark-in-review")
    public ResponseEntity<SafetyReportResponse> markReportInReview(
            @PathVariable UUID reportId,
            @Valid @RequestBody AdminSafetyReportDecisionRequest request
    ) {
        return ResponseEntity.ok(safetyReportService.markReportInReview(reportId, request));
    }

    @Operation(
            summary = "Resolve a safety report",
            description = "Admin only. Resolves the given safety report with the supplied decision details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety report resolved"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "404", description = "Safety report not found")
    })
    @PostMapping("/{reportId}/resolve")
    public ResponseEntity<SafetyReportResponse> resolveReport(
            @PathVariable UUID reportId,
            @Valid @RequestBody AdminSafetyReportDecisionRequest request
    ) {
        return ResponseEntity.ok(safetyReportService.resolveReport(reportId, request));
    }

    @Operation(
            summary = "Dismiss a safety report",
            description = "Admin only. Dismisses the given safety report with the supplied decision details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safety report dismissed"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "404", description = "Safety report not found")
    })
    @PostMapping("/{reportId}/dismiss")
    public ResponseEntity<SafetyReportResponse> dismissReport(
            @PathVariable UUID reportId,
            @Valid @RequestBody AdminSafetyReportDecisionRequest request
    ) {
        return ResponseEntity.ok(safetyReportService.dismissReport(reportId, request));
    }
}