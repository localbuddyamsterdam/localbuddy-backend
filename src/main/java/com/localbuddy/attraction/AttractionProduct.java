package com.localbuddy.attraction;

import java.math.BigDecimal;

/**
 * A third-party ticketed attraction (museum, landmark, tour) as served to the frontend —
 * normalized from the provider's product shape so the UI never depends on Tiqets field names.
 *
 * <p>{@code ticketUrl} is always present (the provider's checkout page, carrying our partner
 * id so link-out sales earn affiliate commission). In-app booking through our own checkout is
 * additionally possible only when the distributor API is enabled (see AttractionStatusResponse).
 */
public record AttractionProduct(
        String id,
        String title,
        String summary,
        String cityName,
        BigDecimal priceFrom,
        String currency,
        String imageUrl,
        /** Provider rating 0–10 (Tiqets scale), null when unrated. */
        BigDecimal rating,
        /** Typical visit duration as displayed by the provider (e.g. "2 hours"), may be null. */
        String duration,
        /** Whether the product needs a specific entry timeslot picked at booking time. */
        boolean requiresTimeslot,
        String ticketUrl
) {
}
