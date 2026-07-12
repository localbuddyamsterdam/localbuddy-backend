package com.localbuddy.experience;

import java.util.UUID;

public record ExperienceCategoryResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String imageUrl,
        boolean active,
        Integer displayOrder
) {
}
