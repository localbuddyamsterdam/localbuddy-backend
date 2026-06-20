package com.localbuddy.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ItineraryRequest(
        @NotBlank @Size(max = 120) String city,
        @Size(max = 1000) String interests,
        Integer durationHours,
        Integer partySize
) {
}
