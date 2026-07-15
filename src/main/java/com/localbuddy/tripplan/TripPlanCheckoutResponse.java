package com.localbuddy.tripplan;

import com.localbuddy.payment.PaymentGroupResponse;

import java.util.List;

/**
 * Result of "book my trip": one payment group (with its Stripe checkout link and the
 * successfully created bookings) plus any items that could not be booked — the customer
 * pays for what was secured and sees exactly what was skipped and why.
 */
public record TripPlanCheckoutResponse(
        PaymentGroupResponse paymentGroup,
        List<TripPlanSkippedItem> skippedItems
) {
}
