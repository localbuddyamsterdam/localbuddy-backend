package com.localbuddy.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/reliability")
@Tag(name = "Admin - Reliability", description = "Cancellation and no-show counts per host, experience and customer")
@SecurityRequirement(name = "bearerAuth")
public class AdminReliabilityController {

    private final ReliabilityService reliabilityService;

    public AdminReliabilityController(ReliabilityService reliabilityService) {
        this.reliabilityService = reliabilityService;
    }

    @Operation(summary = "Reliability summary for a host (local profile)")
    @GetMapping("/hosts/{localProfileId}")
    public ResponseEntity<ReliabilitySummaryResponse> forHost(@PathVariable UUID localProfileId) {
        return ResponseEntity.ok(reliabilityService.forHost(localProfileId));
    }

    @Operation(summary = "Reliability summary for an experience")
    @GetMapping("/experiences/{experienceId}")
    public ResponseEntity<ReliabilitySummaryResponse> forExperience(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(reliabilityService.forExperience(experienceId));
    }

    @Operation(summary = "Reliability summary for a customer")
    @GetMapping("/users/{userId}")
    public ResponseEntity<ReliabilitySummaryResponse> forCustomer(@PathVariable UUID userId) {
        return ResponseEntity.ok(reliabilityService.forCustomer(userId));
    }
}
