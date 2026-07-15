package com.localbuddy.availability;

/** Lifecycle of a recurring availability schedule. */
public enum ScheduleStatus {
    /** Generating slots and taking bookings. */
    ACTIVE,
    /** Temporarily off — no new slots are materialised and future unbooked slots are blocked. */
    PAUSED
}
