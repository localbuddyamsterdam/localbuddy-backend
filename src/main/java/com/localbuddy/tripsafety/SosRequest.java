package com.localbuddy.tripsafety;

import jakarta.validation.constraints.Size;

public record SosRequest(
        Double latitude,
        Double longitude,
        @Size(max = 2000) String message
) {
}
