package com.localbuddy.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Self-service update of the authenticated user's own basic profile. */
public record UpdateProfileRequest(

        @NotBlank(message = "Full name is required")
        @Size(max = 150, message = "Full name cannot exceed 150 characters")
        String fullName,

        @Size(max = 30, message = "Phone cannot exceed 30 characters")
        String phone,

        /** Languages the user speaks; null leaves the stored value unchanged. */
        @Size(max = 15, message = "At most 15 languages")
        List<@Size(max = 40, message = "Language name too long") String> languages
) {
}
