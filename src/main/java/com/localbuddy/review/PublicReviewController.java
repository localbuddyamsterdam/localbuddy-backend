package com.localbuddy.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public")
@Tag(name = "Public Reviews", description = "Public endpoints for reading reviews without authentication")
public class PublicReviewController {

    private final ReviewService reviewService;

    public PublicReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(
            summary = "Get reviews for a local profile",
            description = "Returns all publicly visible reviews for the given local profile. Public; no authentication required."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviews retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Local profile not found")
    })
    @GetMapping("/local-profiles/{localProfileId}/reviews")
    public ResponseEntity<List<ReviewResponse>> getReviewsForLocalProfile(
            @PathVariable UUID localProfileId
    ) {
        return ResponseEntity.ok(reviewService.getPublicReviewsForLocalProfile(localProfileId));
    }

    @Operation(
            summary = "Get reviews for an experience",
            description = "Returns all publicly visible reviews for the given experience. Public; no authentication required."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviews retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Experience not found")
    })
    @GetMapping("/experiences/{experienceId}/reviews")
    public ResponseEntity<List<ReviewResponse>> getReviewsForExperience(
            @PathVariable UUID experienceId
    ) {
        return ResponseEntity.ok(reviewService.getPublicReviewsForExperience(experienceId));
    }
}