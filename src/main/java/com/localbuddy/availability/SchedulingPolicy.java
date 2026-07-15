package com.localbuddy.availability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Enforces that a host can't be in two places at once. Two sessions of the SAME
 * experience simply may not overlap; two sessions of DIFFERENT experiences need a
 * travel gap plus on-site arrival lead between them, because the host has to get
 * to a new location and be there early.
 */
@Component
public class SchedulingPolicy {

    private final Duration travelGap;
    private final Duration arrivalLead;

    public SchedulingPolicy(
            @Value("${app.scheduling.travel-gap-minutes:30}") long travelGapMinutes,
            @Value("${app.scheduling.arrival-lead-minutes:15}") long arrivalLeadMinutes) {
        this.travelGap = Duration.ofMinutes(travelGapMinutes);
        this.arrivalLead = Duration.ofMinutes(arrivalLeadMinutes);
    }

    /** Gap required between two sessions given whether they are the same experience/location. */
    public Duration requiredGap(boolean sameExperience) {
        return sameExperience ? Duration.ZERO : travelGap.plus(arrivalLead);
    }

    public long travelGapMinutes() {
        return travelGap.toMinutes();
    }

    public long arrivalLeadMinutes() {
        return arrivalLead.toMinutes();
    }

    /**
     * True if a candidate session [candStart, candEnd] for {@code candExperienceId} conflicts with an
     * existing session [otherStart, otherEnd] of {@code otherExperienceId}, honouring the required gap.
     */
    public boolean conflicts(UUID candExperienceId, Instant candStart, Instant candEnd,
                             UUID otherExperienceId, Instant otherStart, Instant otherEnd) {
        Duration gap = requiredGap(candExperienceId.equals(otherExperienceId));
        boolean candidateFullyBefore = !candEnd.plus(gap).isAfter(otherStart);
        boolean candidateFullyAfter = !otherEnd.plus(gap).isAfter(candStart);
        return !(candidateFullyBefore || candidateFullyAfter);
    }
}
