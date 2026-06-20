package com.localbuddy.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ListingAssistantRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 120) String city,
        @Size(max = 120) String category,
        @Size(max = 2000) String highlights
) {
}
