package com.localbuddy.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin composes a platform announcement to all hosts. */
public record PlatformAnnouncementRequest(

        @NotBlank(message = "Subject is required")
        @Size(max = 200)
        String subject,

        @NotBlank(message = "Body is required")
        @Size(max = 5000)
        String body
) {
}
