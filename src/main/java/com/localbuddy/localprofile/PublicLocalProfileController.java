package com.localbuddy.localprofile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/local-profiles")
@Tag(name = "Public Local Profiles", description = "Public, unauthenticated browsing of approved local (host) profiles")
public class PublicLocalProfileController {

    private final LocalProfileService localProfileService;

    public PublicLocalProfileController(LocalProfileService localProfileService) {
        this.localProfileService = localProfileService;
    }

    @Operation(
            summary = "List approved local profiles",
            description = "Public endpoint. Returns approved local (host) profiles, optionally filtered by city."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved local profiles retrieved successfully")
    })
    @GetMapping
    public ResponseEntity<List<LocalProfileResponse>> getApprovedLocalProfiles(
            @RequestParam(required = false) String city
    ) {
        return ResponseEntity.ok(localProfileService.getApprovedLocalProfiles(city));
    }

    @Operation(
            summary = "Get approved local profile by ID",
            description = "Public endpoint. Returns a single approved local (host) profile by its ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local profile retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Approved local profile not found")
    })
    @GetMapping("/{profileId}")
    public ResponseEntity<LocalProfileResponse> getApprovedLocalProfileById(
            @PathVariable UUID profileId
    ) {
        return ResponseEntity.ok(localProfileService.getApprovedLocalProfileById(profileId));
    }
}