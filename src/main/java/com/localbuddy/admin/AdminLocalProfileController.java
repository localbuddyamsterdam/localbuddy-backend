package com.localbuddy.admin;

import com.localbuddy.localprofile.AdminLocalProfileReviewRequest;
import com.localbuddy.localprofile.BulkSetHostCommissionRequest;
import com.localbuddy.localprofile.LocalProfileResponse;
import com.localbuddy.localprofile.LocalProfileService;
import com.localbuddy.localprofile.SetHostCommissionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/local-profiles")
@Tag(name = "Admin - Local Profiles", description = "Admin endpoints for reviewing and moderating local (host) profile applications")
public class AdminLocalProfileController {

    private final LocalProfileService localProfileService;

    public AdminLocalProfileController(LocalProfileService localProfileService) {
        this.localProfileService = localProfileService;
    }

    @Operation(
            summary = "List pending local profiles",
            description = "Returns local profile applications awaiting moderation review. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending local profiles retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/pending")
    public ResponseEntity<List<LocalProfileResponse>> getPendingLocalProfiles() {
        return ResponseEntity.ok(localProfileService.getPendingLocalProfiles());
    }

    @Operation(
            summary = "Approve a local profile",
            description = "Approves a pending local profile so the host can publish experiences. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile approved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PostMapping("/{profileId}/approve")
    public ResponseEntity<LocalProfileResponse> approveLocalProfile(@PathVariable UUID profileId) {
        return ResponseEntity.ok(localProfileService.approveLocalProfile(profileId));
    }

    @Operation(
            summary = "Reject a local profile",
            description = "Rejects a pending local profile with a moderation reason. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile rejected successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid moderation request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PostMapping("/{profileId}/reject")
    public ResponseEntity<LocalProfileResponse> rejectLocalProfile(
            @PathVariable UUID profileId,
            @Valid @RequestBody AdminLocalProfileReviewRequest request
    ) {
        return ResponseEntity.ok(localProfileService.rejectLocalProfile(profileId, request));
    }

    @Operation(
            summary = "Request changes to a local profile",
            description = "Sends a pending local profile back to the host with requested changes. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changes requested successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid moderation request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PostMapping("/{profileId}/request-changes")
    public ResponseEntity<LocalProfileResponse> requestChangesForLocalProfile(
            @PathVariable UUID profileId,
            @Valid @RequestBody AdminLocalProfileReviewRequest request
    ) {
        return ResponseEntity.ok(localProfileService.requestChangesForLocalProfile(profileId, request));
    }

    @Operation(
            summary = "Set or clear a host's commission override",
            description = "Sets the per-host commission rate (fraction, e.g. 0.15 = 15%), which "
                    + "supersedes category/city/platform rules for this host's experiences. Send a null "
                    + "commissionRate to clear the override. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission override updated"),
            @ApiResponse(responseCode = "400", description = "Rate out of range (0..max-commission-rate)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PutMapping("/{profileId}/commission")
    public ResponseEntity<LocalProfileResponse> setHostCommission(
            @PathVariable UUID profileId,
            @RequestBody SetHostCommissionRequest request
    ) {
        return ResponseEntity.ok(localProfileService.setCommissionOverride(profileId, request.commissionRate()));
    }

    @Operation(
            summary = "Bulk set or clear host commission overrides",
            description = "Applies the same commission rate (fraction) to every listed host profile "
                    + "in one atomic batch; null commissionRate clears the override on all. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission overrides updated"),
            @ApiResponse(responseCode = "400", description = "Rate out of range (0..max-commission-rate)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "One or more profiles not found")
    })
    @PutMapping("/commission")
    public ResponseEntity<List<LocalProfileResponse>> setHostCommissionBulk(
            @Valid @RequestBody BulkSetHostCommissionRequest request
    ) {
        return ResponseEntity.ok(
                localProfileService.setCommissionOverrideBulk(request.profileIds(), request.commissionRate()));
    }
}