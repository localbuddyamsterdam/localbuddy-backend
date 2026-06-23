package com.localbuddy.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        ConversationType type,
        UUID experienceId,
        UUID bookingId,
        String subject,
        List<Participant> participants,
        Instant lastMessageAt,
        long unreadCount,
        Instant createdAt
) {
    public record Participant(UUID userId, String name, ParticipantRole role) {
    }
}
