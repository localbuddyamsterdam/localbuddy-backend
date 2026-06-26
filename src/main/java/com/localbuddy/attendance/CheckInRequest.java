package com.localbuddy.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * A check-in body from an authenticated guest or a host. Coordinates come from the browser
 * Geolocation API; accuracyMeters is its reported horizontal accuracy (required for guests).
 */
public record CheckInRequest(
        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude out of range")
        @DecimalMax(value = "90.0", message = "Latitude out of range")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude out of range")
        @DecimalMax(value = "180.0", message = "Longitude out of range")
        BigDecimal longitude,

        @PositiveOrZero(message = "Accuracy cannot be negative")
        Double accuracyMeters
) {
}
