package com.localbuddy.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service update of the authenticated user's own basic profile. */
public record UpdateProfileRequest(

        @NotBlank(message = "Full name is required")
        @Size(max = 150, message = "Full name cannot exceed 150 characters")
        String fullName,

        @Size(max = 30, message = "Phone cannot exceed 30 characters")
        String phone
) {
}
