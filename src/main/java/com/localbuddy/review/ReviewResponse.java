package com.localbuddy.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        UUID bookingId,
        ReviewDirection direction,
        UUID reviewerUserId,
        UUID revieweeUserId,
        UUID localProfileId,
        UUID experienceId,
        Integer rating,
        String comment,
        ReviewStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}