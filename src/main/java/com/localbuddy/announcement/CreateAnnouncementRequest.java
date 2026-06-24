package com.localbuddy.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Host composes an announcement to their followers and/or guests. */
public record CreateAnnouncementRequest(

        @NotNull(message = "Audience is required")
        AnnouncementAudience audience,

        @NotBlank(message = "Subject is required")
        @Size(max = 200)
        String subject,

        @NotBlank(message = "Body is required")
        @Size(max = 5000)
        String body
) {
}
