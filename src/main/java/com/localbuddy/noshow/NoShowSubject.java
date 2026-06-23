package com.localbuddy.noshow;

/** Which party a no-show report is about. */
public enum NoShowSubject {
    /** The host failed to show up (reported by the customer; refund on approval). */
    HOST,
    /** The customer failed to show up (reported by the host; informational only). */
    CUSTOMER
}
