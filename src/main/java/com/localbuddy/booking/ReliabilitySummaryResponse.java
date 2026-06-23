package com.localbuddy.booking;

import java.util.UUID;

/**
 * Cancellation and no-show counts for a host, an experience, or a customer.
 * Counts are derived on demand, so admins clearing a no-show flag are reflected immediately.
 * No-shows for a host/experience are counted once per slot occurrence.
 */
public record ReliabilitySummaryResponse(
        String scope,
        UUID id,
        long cancellations,
        long noShows,
        long completedBookings
) {
}
