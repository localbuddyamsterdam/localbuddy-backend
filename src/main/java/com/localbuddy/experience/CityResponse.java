package com.localbuddy.experience;

import java.math.BigDecimal;
import java.util.UUID;

public record CityResponse(
        UUID id,
        String name,
        String slug,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean active,
        Integer displayOrder
) {
}
