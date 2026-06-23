package com.localbuddy.noshow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/no-show")
@Tag(name = "Admin - No-show", description = "Admin verification of no-show reports")
@SecurityRequirement(name = "bearerAuth")
public class AdminNoShowController {

    private final NoShowService noShowService;

    public AdminNoShowController(NoShowService noShowService) {
        this.noShowService = noShowService;
    }

    @Operation(summary = "List no-show reports",
            description = "Pass pendingOnly=true to see only reports awaiting verification.")
    @GetMapping("/reports")
    public ResponseEntity<List<NoShowReportResponse>> listReports(
            @RequestParam(defaultValue = "false") boolean pendingOnly
    ) {
        return ResponseEntity.ok(noShowService.listReports(pendingOnly));
    }

    @Operation(summary = "Approve a no-show report",
            description = "Host no-show -> full refund to the customer; customer no-show -> informational. "
                    + "Flags the booking either way.")
    @PostMapping("/reports/{reportId}/approve")
    public ResponseEntity<NoShowReportResponse> approve(
            @PathVariable UUID reportId,
            @RequestBody(required = false) ResolveNoShowReportRequest body
    ) {
        return ResponseEntity.ok(noShowService.approve(reportId, body));
    }

    @Operation(summary = "Reject a no-show report")
    @PostMapping("/reports/{reportId}/reject")
    public ResponseEntity<NoShowReportResponse> reject(
            @PathVariable UUID reportId,
            @RequestBody(required = false) ResolveNoShowReportRequest body
    ) {
        return ResponseEntity.ok(noShowService.reject(reportId, body));
    }

    @Operation(summary = "Remove a no-show flag from a booking",
            description = "Clears the no-show flag (does not reverse any refund already issued).")
    @DeleteMapping("/bookings/{bookingId}/flag")
    public ResponseEntity<Void> removeFlag(@PathVariable UUID bookingId) {
        noShowService.removeFlag(bookingId);
        return ResponseEntity.noContent().build();
    }
}
