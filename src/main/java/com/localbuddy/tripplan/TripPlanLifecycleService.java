package com.localbuddy.tripplan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Trip-plan lifecycle sweep: plans whose trip has ended flip ACTIVE → ARCHIVED, so
 * "My itineraries" can separate upcoming trips from past ones and share links become
 * view-only (checkout, swap, and refine all require an ACTIVE plan).
 *
 * <p>The cutoff lags one extra day behind UTC so a trip is never archived while its last
 * day is still running somewhere on Earth.
 */
@Service
public class TripPlanLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TripPlanLifecycleService.class);

    private final TripPlanRepository tripPlanRepository;

    public TripPlanLifecycleService(TripPlanRepository tripPlanRepository) {
        this.tripPlanRepository = tripPlanRepository;
    }

    @Scheduled(fixedDelayString = "${app.ai.trip-planner.archive-processor-delay-ms:21600000}")
    @Transactional
    public void archiveEndedPlans() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        int archived = tripPlanRepository.archiveEndedPlans(
                TripPlanStatus.ARCHIVED, TripPlanStatus.ACTIVE, cutoff);
        if (archived > 0) {
            log.info("Archived {} ended trip plan(s) (end date before {})", archived, cutoff);
        }
    }
}
