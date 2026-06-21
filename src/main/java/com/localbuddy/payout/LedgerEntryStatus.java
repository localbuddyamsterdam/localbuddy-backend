package com.localbuddy.payout;

public enum LedgerEntryStatus {
    /** Earned but still within the hold window. */
    PENDING,
    /** Past the hold window; payable in the next payout. */
    AVAILABLE,
    /** Settled into a paid payout. */
    PAID,
    /** Cancelled before payout (e.g. booking refunded while still on hold). */
    REVERSED
}
