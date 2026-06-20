package com.localbuddy.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderUserId,
        String body,
        Instant readAt,
        Instant createdAt
) {
}
