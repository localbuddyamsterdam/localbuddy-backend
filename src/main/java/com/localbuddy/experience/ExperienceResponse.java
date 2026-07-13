package com.localbuddy.experience;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExperienceResponse(
        UUID id,
        UUID localProfileId,
        UUID categoryId,
        String categoryName,
        String categorySlug,
        List<UUID> categoryIds,
        UUID cityId,
        String cityName,
        String citySlug,
        String country,
        String title,
        String slug,
        String description,
        String meetingArea,
        Integer durationMinutes,
        BigDecimal priceAmount,
        String currency,
        Integer maxGuests,
        BigDecimal latitude,
        BigDecimal longitude,
        BookingMode bookingMode,
        BigDecimal privatePrice,
        BigDecimal priceNetAmount,
        PriceInputMode priceInputMode,
        String safetyNotes,
        String shortDescription,
        TransportMode transportMode,
        String inclusions,
        String exclusions,
        String endLocation,
        String reasonsToBook,
        Integer minimumAge,
        ExperienceStatus status,
        Instant createdAt,
        Instant updatedAt,
        ExternalListingType externalListingType,
        String externalListingDetails,
        CoverImage coverImage
) {
    public record CoverImage(UUID id, String url, String caption) {}
}