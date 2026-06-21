package com.localbuddy.payout;

public enum LedgerEntryType {
    /** Host's earnings from a paid booking. */
    EARNING,
    /** Negative clawback when a paid-out booking is later refunded. */
    REVERSAL,
    /** Manual admin adjustment (+/-). */
    ADJUSTMENT
}
