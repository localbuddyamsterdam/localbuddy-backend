package com.localbuddy.gdpr;

import java.time.Instant;
import java.util.UUID;

public record DataDeletionRequestResponse(
        UUID id,
        UUID userId,
        String reason,
        DataDeletionStatus status,
        String adminNote,
        Instant createdAt,
        Instant processedAt
) {
    public static DataDeletionRequestResponse from(DataDeletionRequest request) {
        return new DataDeletionRequestResponse(
                request.getId(),
                request.getUser().getId(),
                request.getReason(),
                request.getStatus(),
                request.getAdminNote(),
                request.getCreatedAt(),
                request.getProcessedAt()
        );
    }
}
