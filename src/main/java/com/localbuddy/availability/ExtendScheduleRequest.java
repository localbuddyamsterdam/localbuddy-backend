package com.localbuddy.availability;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Moves a schedule's end date (extend or shrink); future unbooked slots are adjusted to match. */
public record ExtendScheduleRequest(

        @NotNull(message = "New end date is required")
        LocalDate endDate
) {
}
