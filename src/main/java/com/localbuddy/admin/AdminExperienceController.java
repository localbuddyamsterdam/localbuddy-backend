package com.localbuddy.admin;

import com.localbuddy.experience.ExperienceResponse;
import com.localbuddy.experience.ExperienceService;
import com.localbuddy.experience.SetExperienceCommissionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/experiences")
@Tag(name = "Admin - Experiences", description = "Admin endpoints for reviewing and moderating host-submitted experiences")
public class AdminExperienceController {

    private final ExperienceService experienceService;

    public AdminExperienceController(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @Operation(
            summary = "List pending experiences",
            description = "Returns experiences awaiting moderation review. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending experiences retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/pending")
    public ResponseEntity<List<ExperienceResponse>> getPendingExperiences() {
        return ResponseEntity.ok(experienceService.getPendingExperiences());
    }

    @Operation(
            summary = "Approve an experience",
            description = "Approves a pending experience, making it publicly bookable. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience approved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PostMapping("/{experienceId}/approve")
    public ResponseEntity<ExperienceResponse> approveExperience(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(experienceService.approveExperience(experienceId));
    }

    @Operation(
            summary = "Reject an experience",
            description = "Rejects a pending experience so it is not published. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience rejected successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PostMapping("/{experienceId}/reject")
    public ResponseEntity<ExperienceResponse> rejectExperience(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(experienceService.rejectExperience(experienceId));
    }

    @Operation(
            summary = "Set or clear an experience's commission override",
            description = "Sets the per-experience commission rate (fraction, e.g. 0.15 = 15%), which "
                    + "supersedes host/category/city/platform rules for this experience. Send a null "
                    + "commissionRate to clear the override. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission override updated"),
            @ApiResponse(responseCode = "400", description = "Rate out of range (0..max-commission-rate)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PutMapping("/{experienceId}/commission")
    public ResponseEntity<ExperienceResponse> setCommissionOverride(
            @PathVariable UUID experienceId,
            @RequestBody SetExperienceCommissionRequest request) {
        return ResponseEntity.ok(
                experienceService.setCommissionOverride(experienceId, request.commissionRate()));
    }
}