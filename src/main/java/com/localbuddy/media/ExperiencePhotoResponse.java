package com.localbuddy.media;

import java.time.Instant;
import java.util.UUID;

public record ExperiencePhotoResponse(
        UUID id,
        UUID experienceId,
        String url,
        String caption,
        String contentType,
        Long sizeBytes,
        int sortOrder,
        boolean cover,
        Instant createdAt
) {
    public static ExperiencePhotoResponse from(ExperiencePhoto photo) {
        return new ExperiencePhotoResponse(
                photo.getId(),
                photo.getExperience().getId(),
                photo.getUrl(),
                photo.getCaption(),
                photo.getContentType(),
                photo.getSizeBytes(),
                photo.getSortOrder(),
                photo.isCover(),
                photo.getCreatedAt()
        );
    }
}
