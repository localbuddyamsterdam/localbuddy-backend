package com.localbuddy.booking;

import jakarta.validation.constraints.NotNull;

/**
 * Admin set/reset of a booking's attendance flag (the platform's no-show verdict on the
 * booking itself). {@link AttendanceOutcome#NONE} clears it. This records the outcome on the
 * booking; it is intentionally simple and does not itself recompute reliability scores.
 */
public record AdminSetAttendanceRequest(
        @NotNull(message = "Attendance outcome is required") AttendanceOutcome outcome
) {
}
