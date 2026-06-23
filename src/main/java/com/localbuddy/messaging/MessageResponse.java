package com.localbuddy.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderUserId,
        String senderName,
        ParticipantRole senderRole,
        /** Ready-to-display label: "Admin (Sarah Chen)" for admins, otherwise the sender's name. */
        String senderLabel,
        String body,
        Instant createdAt
) {
}
