package com.localbuddy.booking;

import java.util.Set;

public enum BookingStatus {
    REQUESTED,
    ACCEPTED,
    PENDING_PAYMENT,
    CONFIRMED,
    DECLINED,
    CANCELLED_BY_LOGGED_IN_USER,
    CANCELLED_BY_LOCAL,
    CANCELLED_BY_ADMIN,
    CANCELLED_MINIMUM_NOT_MET,
    COMPLETED,
    EXPIRED;

    /** Statuses that still hold a seat on a slot (not yet terminal). */
    public static final Set<BookingStatus> ACTIVE =
            Set.of(REQUESTED, ACCEPTED, PENDING_PAYMENT, CONFIRMED);
}