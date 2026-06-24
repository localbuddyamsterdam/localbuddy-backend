package com.localbuddy.newsletter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin composes a newsletter and sends it to confirmed subscribers in an audience. */
public record NewsletterBroadcastRequest(

        @NotNull(message = "Audience is required")
        NewsletterAudience audience,

        @NotBlank(message = "Subject is required")
        @Size(max = 200)
        String subject,

        @NotBlank(message = "Body is required")
        @Size(max = 20000)
        String body
) {
}
