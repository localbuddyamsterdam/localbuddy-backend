package com.localbuddy.availability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Edits an existing schedule's pattern/dates/capacity. The experience can't change.
 * Future unbooked slots from this schedule are regenerated; booked ones are kept.
 */
public record UpdateScheduleRequest(

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate,

        @NotEmpty(message = "At least one weekly rule is required")
        List<CreateScheduleRequest.@Valid WeeklyRule> weekly,

        @NotNull(message = "Capacity is required")
        @Min(value = 1, message = "Capacity must be at least 1")
        Integer capacity,

        boolean privateEligible
) {
}
