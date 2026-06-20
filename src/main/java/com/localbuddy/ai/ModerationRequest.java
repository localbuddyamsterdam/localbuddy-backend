package com.localbuddy.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModerationRequest(
        @NotBlank @Size(max = 5000) String text
) {
}
