package com.localbuddy.incident;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Admin-only view and controls for platform status. Under {@code /api/admin/**} so it is gated to
 * {@code ROLE_ADMIN} by {@code SecurityConfig}.
 *
 * <ul>
 *   <li>{@code GET  /api/admin/system-status} — full detail (current/last incident, last probe).</li>
 *   <li>{@code POST /api/admin/system-status/test-alert} — fire a test alert to verify the email path.</li>
 *   <li>{@code POST /api/admin/system-status/banner} — raise/clear a manual maintenance banner.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/system-status")
@Tag(name = "System status (admin)", description = "Operator view and controls for platform status")
public class AdminSystemStatusController {

    private final SystemStatusService systemStatusService;
    private final IncidentAlertService alertService;

    public AdminSystemStatusController(SystemStatusService systemStatusService,
                                       IncidentAlertService alertService) {
        this.systemStatusService = systemStatusService;
        this.alertService = alertService;
    }

    @Operation(summary = "Detailed platform status")
    @GetMapping
    public ResponseEntity<IncidentStatusResponse> status() {
        return ResponseEntity.ok(systemStatusService.adminView());
    }

    @Operation(summary = "Send a test incident alert",
            description = "Dispatches a synthetic incident email to the configured recipients so the "
                    + "alerting path can be verified before a real outage.")
    @PostMapping("/test-alert")
    public ResponseEntity<TestAlertResponse> testAlert() {
        List<String> recipients = alertService.sendTestAlert(Instant.now());
        return ResponseEntity.ok(new TestAlertResponse(!recipients.isEmpty(), recipients));
    }

    @Operation(summary = "Raise or clear a manual maintenance banner",
            description = "Independent of automatic detection — use for planned maintenance or a "
                    + "known issue. Set active=false to clear.")
    @PostMapping("/banner")
    public ResponseEntity<SystemStatusResponse> banner(@Valid @RequestBody BannerRequest request) {
        if (request != null && request.active()) {
            systemStatusService.raiseManualBanner(request.message(), Instant.now());
        } else {
            systemStatusService.clearManualBanner();
        }
        return ResponseEntity.ok(systemStatusService.publicView());
    }

    public record BannerRequest(boolean active, @Size(max = 500) String message) {
    }

    public record TestAlertResponse(boolean sent, List<String> recipients) {
    }
}
