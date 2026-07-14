package com.localbuddy.referral;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin management of the single referral reward configuration. Only one config
 * may exist at a time; creating a second is rejected. Deleting or deactivating it
 * reverts the platform to the built-in default reward amount.
 */
@RestController
@RequestMapping("/api/admin/referral-reward-config")
@Tag(name = "Admin - Referral Rewards", description = "Admin-only management of the referral reward amount")
public class AdminReferralController {

    private final ReferralRewardConfigService configService;

    public AdminReferralController(ReferralRewardConfigService configService) {
        this.configService = configService;
    }

    @Operation(summary = "Get the referral reward configuration (or the platform defaults if none exists)")
    @GetMapping
    public ResponseEntity<ReferralRewardConfigResponse> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @Operation(summary = "Create the referral reward configuration (fails if one already exists)")
    @PostMapping
    public ResponseEntity<ReferralRewardConfigResponse> createConfig(
            @Valid @RequestBody ReferralRewardConfigRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createConfig(request));
    }

    @Operation(summary = "Update the referral reward configuration")
    @PutMapping
    public ResponseEntity<ReferralRewardConfigResponse> updateConfig(
            @Valid @RequestBody ReferralRewardConfigRequest request
    ) {
        return ResponseEntity.ok(configService.updateConfig(request));
    }

    @Operation(summary = "Activate or deactivate the referral reward configuration")
    @PatchMapping("/active")
    public ResponseEntity<ReferralRewardConfigResponse> setActive(@RequestParam boolean active) {
        return ResponseEntity.ok(configService.setActive(active));
    }

    @Operation(summary = "Delete the referral reward configuration (reverts to the default reward amount)")
    @DeleteMapping
    public ResponseEntity<Void> deleteConfig() {
        configService.deleteConfig();
        return ResponseEntity.noContent().build();
    }
}
