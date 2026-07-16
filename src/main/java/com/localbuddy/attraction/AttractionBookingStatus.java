package com.localbuddy.attraction;

/** Lifecycle of a third-party attraction ticket order. */
public enum AttractionBookingStatus {
    /** Created locally, provider order not (yet) confirmed. */
    PENDING,
    /** Provider confirmed the order; tickets issued. */
    CONFIRMED,
    /** Provider rejected or errored; see errorMessage. */
    FAILED,
    CANCELLED
}
