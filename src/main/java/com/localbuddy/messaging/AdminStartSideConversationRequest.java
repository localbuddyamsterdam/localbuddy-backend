package com.localbuddy.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Admin opens a private side conversation with one customer or host. */
public record AdminStartSideConversationRequest(

        @NotNull(message = "Target user id is required")
        UUID targetUserId,

        /** Optional context — the experience the side conversation relates to. */
        UUID experienceId,

        @Size(max = 200, message = "Subject cannot exceed 200 characters")
        String subject,

        /** Optional first message to post immediately. */
        @Size(max = 5000, message = "Message cannot exceed 5000 characters")
        String body
) {
}
