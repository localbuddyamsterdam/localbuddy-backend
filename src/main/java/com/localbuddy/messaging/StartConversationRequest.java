package com.localbuddy.messaging;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Start (or reuse) a conversation with the host of an experience. */
public record StartConversationRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId
) {
}
