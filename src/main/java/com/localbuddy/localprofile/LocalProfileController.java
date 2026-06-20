package com.localbuddy.localprofile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/local-profiles")
@Tag(name = "Local Profiles", description = "Endpoints for managing local (host) profiles and onboarding")
@SecurityRequirement(name = "bearerAuth")
public class LocalProfileController {

    private final LocalProfileService localProfileService;

    public LocalProfileController(LocalProfileService localProfileService) {
        this.localProfileService = localProfileService;
    }

    @Operation(
            summary = "Create my local profile",
            description = "Creates a new local profile for the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Local profile created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/me")
    public ResponseEntity<LocalProfileResponse> createMyLocalProfile(
            Authentication authentication,
            @Valid @RequestBody CreateLocalProfileRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        LocalProfileResponse response = localProfileService.createMyLocalProfile(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get my local profile",
            description = "Returns the local profile belonging to the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @GetMapping("/me")
    public ResponseEntity<LocalProfileResponse> getMyLocalProfile(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(localProfileService.getMyLocalProfile(userId));
    }

    @Operation(
            summary = "Update my local profile",
            description = "Updates the local profile of the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PutMapping("/me")
    public ResponseEntity<LocalProfileResponse> updateMyLocalProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateLocalProfileRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(localProfileService.updateMyLocalProfile(userId, request));
    }

    @Operation(
            summary = "Submit my local profile for review",
            description = "Submits the authenticated user's local profile so an admin can review it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile submitted successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @PostMapping("/me/submit")
    public ResponseEntity<LocalProfileResponse> submitMyLocalProfile(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(localProfileService.submitMyLocalProfile(userId));
    }

    @Operation(
            summary = "Get my onboarding status",
            description = "Returns the onboarding progress/status for the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Onboarding status retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/onboarding-status")
    public ResponseEntity<LocalOnboardingStatusResponse> getMyOnboardingStatus(
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(localProfileService.getMyOnboardingStatus(userId));
    }
}