package com.localbuddy.experience;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCityRequest(

        @NotBlank(message = "City name is required")
        @Size(max = 100, message = "City name cannot exceed 100 characters")
        String name,

        @NotBlank(message = "Country is required")
        @Size(max = 100, message = "Country cannot exceed 100 characters")
        String country,

        /** Optional city-centre coordinates (for map centring + weather). */
        @DecimalMin(value = "-90.0", message = "Latitude out of range")
        @DecimalMax(value = "90.0", message = "Latitude out of range")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "Longitude out of range")
        @DecimalMax(value = "180.0", message = "Longitude out of range")
        BigDecimal longitude,

        Integer displayOrder
) {
}
