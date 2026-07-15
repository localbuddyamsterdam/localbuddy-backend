package com.localbuddy.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One turn of the public AI chat: role is "user" or "assistant" (client sends full history). */
public record ChatMessage(

        @NotBlank(message = "Message role is required")
        @Pattern(regexp = "user|assistant", message = "Role must be 'user' or 'assistant'")
        String role,

        @NotBlank(message = "Message content is required")
        @Size(max = 2000, message = "A chat message cannot exceed 2000 characters")
        String content
) {
}
