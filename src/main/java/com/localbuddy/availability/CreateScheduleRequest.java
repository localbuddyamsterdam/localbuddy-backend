package com.localbuddy.availability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A persistent weekly availability pattern for one experience: "every Tue/Fri/Sat
 * at 10:00 and 14:00, from today until Sep 30, up to 6 guests". Session length is
 * the experience's own duration; concrete slots are materialised in the city's
 * local time, so the host never enters end times.
 */
public record CreateScheduleRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate,

        @NotEmpty(message = "At least one weekly rule is required")
        List<@Valid WeeklyRule> weekly,

        @NotNull(message = "Capacity is required")
        @Min(value = 1, message = "Capacity must be at least 1")
        Integer capacity,

        boolean privateEligible
) {

    public record WeeklyRule(
            @NotNull(message = "Day of week is required")
            DayOfWeek dayOfWeek,

            @NotEmpty(message = "At least one time is required")
            List<@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Times must be HH:mm") String> times
    ) {
    }
}
