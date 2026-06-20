package com.localbuddy.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews", description = "Endpoints for creating and viewing reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(
            summary = "Create a review",
            description = "Allows the authenticated user to create a new review. The reviewer is taken from the authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            Authentication authentication,
            @Valid @RequestBody CreateReviewRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        ReviewResponse response = reviewService.createReview(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Review a traveler (as host)",
            description = "Lets the host of a completed booking review the traveler. Authenticated host."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request / booking not completed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Booking not found")
    })
    @PostMapping("/as-host")
    public ResponseEntity<ReviewResponse> createTravelerReview(
            Authentication authentication,
            @Valid @RequestBody CreateReviewRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.createTravelerReview(userId, request));
    }

    @Operation(
            summary = "Get my reviews",
            description = "Returns all reviews authored by the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviews retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<List<ReviewResponse>> getMyReviews(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(reviewService.getMyReviews(userId));
    }

    @Operation(
            summary = "Get reviews about me",
            description = "Returns the visible reviews written about the currently authenticated user (as traveler or host)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reviews retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/about-me")
    public ResponseEntity<List<ReviewResponse>> getReceivedReviews(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(reviewService.getReceivedReviews(userId));
    }
}