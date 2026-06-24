package com.localbuddy.experience;

import java.math.BigDecimal;
import java.util.UUID;

/** Lightweight experience marker for map rendering; distanceKm is set when a viewer location is given. */
public record ExperienceMapMarker(
        UUID id,
        String slug,
        String title,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal priceAmount,
        String currency,
        String cityName,
        Double distanceKm
) {
}
