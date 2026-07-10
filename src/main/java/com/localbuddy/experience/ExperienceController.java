package com.localbuddy.experience;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/experiences")
@Tag(name = "Experiences", description = "Endpoints for creating and managing a local's experiences")
@SecurityRequirement(name = "bearerAuth")
public class ExperienceController {

    private final ExperienceService experienceService;

    public ExperienceController(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @Operation(
            summary = "Create my experience",
            description = "Creates a new experience for the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Experience created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<ExperienceResponse> createMyExperience(
            Authentication authentication,
            @Valid @RequestBody CreateExperienceRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        ExperienceResponse response = experienceService.createMyExperience(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get my experiences",
            description = "Returns all experiences belonging to the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experiences retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<List<ExperienceResponse>> getMyExperiences(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.getMyExperiences(userId));
    }

    @Operation(
            summary = "Get my experience by ID",
            description = "Returns a single experience by its ID, if it belongs to the authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @GetMapping("/me/{experienceId}")
    public ResponseEntity<ExperienceResponse> getMyExperienceById(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.getMyExperienceById(userId, experienceId));
    }

    @Operation(
            summary = "Update my experience",
            description = "Updates an existing experience owned by the authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PutMapping("/{experienceId}")
    public ResponseEntity<ExperienceResponse> updateMyExperience(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @Valid @RequestBody UpdateExperienceRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.updateMyExperience(userId, experienceId, request));
    }

    @Operation(
            summary = "Submit my experience for review",
            description = "Submits an experience owned by the authenticated user so an admin can review it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience submitted successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PostMapping("/{experienceId}/submit")
    public ResponseEntity<ExperienceResponse> submitMyExperience(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.submitMyExperience(userId, experienceId));
    }

    @Operation(
            summary = "Unpublish my experience",
            description = "Hides a published (approved) experience from the catalog by pausing it. Reversible via publish."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience unpublished"),
            @ApiResponse(responseCode = "400", description = "Experience is not currently published"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PostMapping("/{experienceId}/unpublish")
    public ResponseEntity<ExperienceResponse> unpublishMyExperience(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.unpublishMyExperience(userId, experienceId));
    }

    @Operation(
            summary = "Re-publish my experience",
            description = "Re-lists a paused experience so it appears in the catalog again."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience re-published"),
            @ApiResponse(responseCode = "400", description = "Experience is not paused"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @PostMapping("/{experienceId}/publish")
    public ResponseEntity<ExperienceResponse> publishMyExperience(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(experienceService.publishMyExperience(userId, experienceId));
    }

    @Operation(
            summary = "Delete my experience",
            description = "Soft-deletes (archives) an experience owned by the authenticated user. Existing bookings are preserved."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Experience deleted"),
            @ApiResponse(responseCode = "400", description = "Experience cannot be deleted"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @DeleteMapping("/{experienceId}")
    public ResponseEntity<Void> deleteMyExperience(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        experienceService.deleteMyExperience(userId, experienceId);
        return ResponseEntity.noContent().build();
    }
}