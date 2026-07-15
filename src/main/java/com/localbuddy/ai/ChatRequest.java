package com.localbuddy.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Public AI chat request. The conversation is stateless server-side: the client sends the
 * whole history each turn (older turns beyond the configured limit are dropped). The last
 * message must be from the user.
 */
public record ChatRequest(

        @NotEmpty(message = "At least one message is required")
        @Size(max = 40, message = "Conversation history is too long")
        List<@Valid ChatMessage> messages,

        @Size(max = 120, message = "City slug cannot exceed 120 characters")
        String citySlug
) {
}
