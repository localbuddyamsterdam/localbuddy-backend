package com.localbuddy.incident;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated status endpoint the frontend polls to decide whether to show the
 * "we're experiencing difficulties" banner. Under {@code /api/public/**} it is covered by the
 * existing permitAll rule.
 *
 * <p>Crucially this reads only in-memory state ({@link SystemStatusService}) and touches no database,
 * so it keeps answering — with {@code degraded:true} — precisely during a database outage.
 */
@RestController
@RequestMapping("/api/public/system-status")
@Tag(name = "System status", description = "Public platform status for the frontend banner")
public class PublicSystemStatusController {

    private final SystemStatusService systemStatusService;

    public PublicSystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @Operation(summary = "Public platform status",
            description = "Returns operational/degraded plus banner copy. No auth, no database access.")
    @GetMapping
    public ResponseEntity<SystemStatusResponse> status() {
        return ResponseEntity.ok()
                // Allow brief caching so frequent polling can't hammer the app, but keep it short
                // enough that the banner clears promptly on recovery.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(15)).cachePublic())
                .body(systemStatusService.publicView());
    }
}
