package com.localbuddy.wishlist;

import com.localbuddy.experience.Experience;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WishlistItemResponse(
        UUID experienceId,
        String title,
        String slug,
        String cityName,
        BigDecimal priceAmount,
        String currency,
        String status,
        Instant addedAt
) {
    public static WishlistItemResponse from(WishlistItem item) {
        Experience e = item.getExperience();
        return new WishlistItemResponse(
                e.getId(),
                e.getTitle(),
                e.getSlug(),
                e.getCity() != null ? e.getCity().getName() : null,
                e.getPriceAmount(),
                e.getCurrency(),
                e.getStatus() != null ? e.getStatus().name() : null,
                item.getCreatedAt()
        );
    }
}
