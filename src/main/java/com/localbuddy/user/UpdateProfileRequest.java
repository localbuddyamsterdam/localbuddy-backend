package com.localbuddy.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Self-service update of the authenticated user's own basic profile. */
public record UpdateProfileRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name cannot exceed 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(min = 2, max = 100, message = "Last name must be between 2 and 100 characters")
        String lastName,

        /** Optional "goes by" name; surfaced in the UI for host and admin accounts. */
        @Size(max = 100, message = "Preferred name cannot exceed 100 characters")
        String preferredName,

        @Size(max = 30, message = "Phone cannot exceed 30 characters")
        String phone,

        /** Languages the user speaks; null leaves the stored value unchanged. */
        @Size(max = 15, message = "At most 15 languages")
        List<@Size(max = 40, message = "Language name too long") String> languages
) {
}
