package com.localbuddy.availability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bulk slot generation from a weekly schedule (Calendly-style): "Mon + Sat at
 * 10:00 and 14:00, from June 1 to Aug 31". Times are wall-clock in the given
 * timezone (default Europe/Amsterdam), so slots stay at 10:00 local across DST.
 */
public record GenerateAvailabilityRequest(

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
        @Max(value = 10, message = "Capacity cannot exceed 10 for MVP")
        Integer capacity,

        /** Slot length; defaults to the experience's duration. */
        @Min(value = 15, message = "Duration must be at least 15 minutes")
        @Max(value = 720, message = "Duration cannot exceed 12 hours")
        Integer durationMinutes,

        /** IANA zone the times are expressed in; defaults to Europe/Amsterdam. */
        String timezone
) {

    public record WeeklyRule(
            @NotNull(message = "Day of week is required")
            DayOfWeek dayOfWeek,

            @NotEmpty(message = "At least one time is required")
            List<@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Times must be HH:mm") String> times
    ) {
    }
}
