package com.localbuddy.admin;

import com.localbuddy.review.AdminReviewModerationRequest;
import com.localbuddy.review.ReviewResponse;
import com.localbuddy.review.ReviewService;
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
@RequestMapping("/api/admin/reviews")
@Tag(name = "Admin - Reviews", description = "Admin endpoints for moderating guest and host reviews")
public class AdminReviewController {

    private final ReviewService reviewService;

    public AdminReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(
            summary = "List reviews",
            description = "Returns all reviews for moderation. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviews retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping
    public ResponseEntity<List<ReviewResponse>> getAdminReviews() {
        return ResponseEntity.ok(reviewService.getAdminReviews());
    }

    @Operation(
            summary = "Hide a review",
            description = "Hides a review from public display following moderation. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review hidden successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid moderation request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Review not found")
    })
    @PostMapping("/{reviewId}/hide")
    public ResponseEntity<ReviewResponse> hideReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody AdminReviewModerationRequest request
    ) {
        return ResponseEntity.ok(reviewService.hideReview(reviewId, request.reason()));
    }

    @Operation(
            summary = "Unhide a review",
            description = "Restores a previously hidden review to public display. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review unhidden successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid moderation request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Review not found")
    })
    @PostMapping("/{reviewId}/unhide")
    public ResponseEntity<ReviewResponse> unhideReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody AdminReviewModerationRequest request
    ) {
        return ResponseEntity.ok(reviewService.unhideReview(reviewId, request.reason()));
    }
}