package com.localbuddy.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/dashboard")
@Tag(name = "Admin - Dashboard", description = "Admin endpoints providing aggregate platform metrics and operational summaries")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @Operation(
            summary = "Get dashboard summary",
            description = "Returns aggregate platform metrics for the admin dashboard. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/summary")
    public ResponseEntity<AdminDashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(adminDashboardService.getSummary());
    }

    @Operation(
            summary = "Get dashboard metrics",
            description = "Returns money, revenue series, operational queues and top experiences/hosts "
                    + "over a rolling window of `days` days (clamped to [1, 365]). Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metrics retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/metrics")
    public ResponseEntity<AdminDashboardMetricsResponse> getMetrics(
            @RequestParam(defaultValue = "30") int days) {
        int clamped = Math.max(1, Math.min(365, days));
        return ResponseEntity.ok(adminDashboardService.metrics(clamped));
    }
}