package com.localbuddy.experience;

import java.time.Instant;
import java.util.UUID;

public record CategorySuggestionResponse(
        UUID id,
        String suggestedName,
        String note,
        SuggestionStatus status,
        UUID suggestedByUserId,
        UUID resultingCategoryId,
        Instant createdAt,
        Instant reviewedAt
) {
}
