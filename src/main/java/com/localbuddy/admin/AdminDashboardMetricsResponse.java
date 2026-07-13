package com.localbuddy.admin;

import java.math.BigDecimal;
import java.util.List;

/**
 * Operational metrics for the admin dashboard over a rolling window of {@code windowDays} days.
 * Field names are consumed verbatim by the admin frontend — do not rename.
 */
public record AdminDashboardMetricsResponse(
        int windowDays,
        Money money,
        List<SeriesPoint> revenueSeries,
        Queues queues,
        List<TopExperience> topExperiences,
        List<TopHost> topHosts
) {

    /** Gross/net money for the current window plus the immediately-preceding window for deltas. */
    public record Money(
            BigDecimal gmv,
            BigDecimal platformRevenue,
            BigDecimal takeRatePct,
            long bookings,
            BigDecimal prevGmv,
            BigDecimal prevPlatformRevenue,
            long prevBookings
    ) {
    }

    /** One calendar-day bucket of the revenue chart. */
    public record SeriesPoint(
            String label,
            BigDecimal value
    ) {
    }

    /** Operational backlog counts surfaced as dashboard action queues. */
    public record Queues(
            long sosOpen,
            long safetyOpen,
            long trustSafetyActionRequired,
            long noShowPending,
            long gdprPending,
            long hostAppsPending,
            long experiencesPending,
            long payoutsPendingOrFailed,
            long paymentsFailedOrRefundPending,
            long reviewsHidden
    ) {
    }

    /** Top-earning experience over the window (revenue proxied by paid GMV). */
    public record TopExperience(
            String id,
            String title,
            BigDecimal revenue
    ) {
    }

    /** Top-earning host over the window (revenue proxied by paid GMV). */
    public record TopHost(
            String id,
            String name,
            BigDecimal revenue
    ) {
    }
}
