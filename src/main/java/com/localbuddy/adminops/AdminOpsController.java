package com.localbuddy.adminops;

import com.localbuddy.experience.ExperienceResponse;
import com.localbuddy.localprofile.LocalProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ops")
@Tag(name = "Admin - Operations", description = "Admin operational endpoints for provisioning local profiles and experiences")
public class AdminOpsController {

    private final AdminOpsService adminOpsService;

    public AdminOpsController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @Operation(
            summary = "Create an approved local profile",
            description = "Admin only. Creates a new, immediately approved local profile on behalf of an existing LOCAL user. "
                    + "Fails with 409 if the user already has a host profile — use the host-application review endpoints to manage it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Local profile created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "409", description = "A host profile already exists for this user")
    })
    @PostMapping("/local-profiles")
    public ResponseEntity<LocalProfileResponse> createOrApproveLocalProfile(
            @Valid @RequestBody AdminCreateLocalProfileRequest request
    ) {
        LocalProfileResponse response = adminOpsService.createOrApproveLocalProfile(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Create an approved experience",
            description = "Admin only. Creates an experience that is approved immediately, bypassing the standard review flow."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Experience created and approved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    })
    @PostMapping("/experiences")
    public ResponseEntity<ExperienceResponse> createApprovedExperience(
            @Valid @RequestBody AdminCreateExperienceRequest request
    ) {
        ExperienceResponse response = adminOpsService.createApprovedExperience(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}