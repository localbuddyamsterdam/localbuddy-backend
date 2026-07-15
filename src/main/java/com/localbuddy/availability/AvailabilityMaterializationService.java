package com.localbuddy.availability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Rolls every active schedule's materialised horizon forward on a timer, so
 * open-ended-feeling schedules keep producing bookable slots without the host
 * re-generating. Each schedule commits in its own transaction.
 */
@Service
public class AvailabilityMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityMaterializationService.class);

    private final AvailabilityScheduleRepository scheduleRepository;
    private final AvailabilityScheduleService scheduleService;

    public AvailabilityMaterializationService(AvailabilityScheduleRepository scheduleRepository,
                                              AvailabilityScheduleService scheduleService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
    }

    @Scheduled(fixedDelayString = "${app.availability.materialization-processor-delay-ms:3600000}")
    public void materializeActiveSchedules() {
        for (AvailabilitySchedule schedule : scheduleRepository.findByStatus(ScheduleStatus.ACTIVE)) {
            UUID id = schedule.getId();
            try {
                int created = scheduleService.materialize(id);
                if (created > 0) {
                    log.debug("Materialized {} slots for schedule {}", created, id);
                }
            } catch (Exception ex) {
                log.warn("Failed to materialize schedule {}: {}", id, ex.getMessage());
            }
        }
    }
}
