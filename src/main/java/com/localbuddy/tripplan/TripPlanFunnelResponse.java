package com.localbuddy.tripplan;

import java.util.List;

/**
 * Trip Genie funnel for the admin dashboard, over a cohort of plans created in the last
 * {@code windowDays}: generated → re-viewed (opened again after the post-generation view)
 * → bundle checkout started → paid, plus the helpfulness votes and token spend that turn
 * model choice into measurable data.
 */
public record TripPlanFunnelResponse(
        int windowDays,
        long generated,
        /** Plans opened at least twice — the traveler came back to it (or shared it). */
        long reViewed,
        /** Plans with at least one bundle checkout (payment group) started. */
        long checkoutStarted,
        /** Plans with at least one PAID bundle checkout. */
        long checkoutPaid,
        long feedbackHelpful,
        long feedbackUnhelpful,
        long inputTokens,
        long outputTokens,
        List<ModelStat> byModel
) {

    /** Per-model volume/quality/cost split. */
    public record ModelStat(
            String model,
            long plans,
            long helpful,
            long unhelpful,
            Integer avgOutputTokens
    ) {
    }
}
