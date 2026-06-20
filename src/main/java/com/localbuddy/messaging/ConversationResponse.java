package com.localbuddy.messaging;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID travelerUserId,
        UUID hostUserId,
        UUID experienceId,
        UUID bookingId,
        Instant lastMessageAt,
        long unreadCount,
        Instant createdAt
) {
}
