package com.localbuddy.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserStatusRequest(

        @NotNull(message = "Status is required")
        UserStatus status,

        @Size(max = 1000, message = "Reason cannot exceed 1000 characters")
        String reason
) {
}