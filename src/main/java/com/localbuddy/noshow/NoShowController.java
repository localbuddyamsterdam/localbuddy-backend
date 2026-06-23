package com.localbuddy.noshow;

import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/no-show")
@Tag(name = "No-show", description = "Customers and hosts report a no-show on their bookings")
@SecurityRequirement(name = "bearerAuth")
public class NoShowController {

    private final NoShowService noShowService;

    public NoShowController(NoShowService noShowService) {
        this.noShowService = noShowService;
    }

    @Operation(
            summary = "Report a no-show on my booking",
            description = "A customer reports the host did not show up (refund claim, admin-reviewed); "
                    + "a host reports the customer did not show up (informational). Allowed up to 48h "
                    + "after the experience start time."
    )
    @PostMapping("/bookings/{bookingId}/report")
    public ResponseEntity<NoShowReportResponse> reportNoShow(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) CreateNoShowReportRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        NoShowReportResponse response = noShowService.reportNoShow(userId, bookingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List the no-show reports I have filed")
    @GetMapping("/me")
    public ResponseEntity<List<NoShowReportResponse>> getMyReports(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(noShowService.getMyReports(userId));
    }
}
