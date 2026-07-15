package com.localbuddy.availability;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        UUID experienceId,
        UUID localProfileId,
        LocalDate startDate,
        LocalDate endDate,
        Integer capacity,
        boolean privateEligible,
        String timezone,
        ScheduleStatus status,
        LocalDate materializedUntil,
        List<WeeklyRuleResponse> weekly,
        Instant createdAt,
        Instant updatedAt
) {

    public record WeeklyRuleResponse(
            DayOfWeek dayOfWeek,
            List<String> times
    ) {
    }
}
