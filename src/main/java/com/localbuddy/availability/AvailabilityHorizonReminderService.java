package com.localbuddy.availability;

import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * Nudges hosts to extend a recurring schedule before it lapses. As the end date
 * approaches, one reminder fires per configured offset bucket (30/7/2/1 days);
 * the notification dedupe key guarantees each fires at most once.
 */
@Service
public class AvailabilityHorizonReminderService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityHorizonReminderService.class);
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Amsterdam");

    private final AvailabilityScheduleRepository scheduleRepository;
    private final NotificationService notificationService;
    private final List<Integer> offsetsDaysAscending;

    public AvailabilityHorizonReminderService(AvailabilityScheduleRepository scheduleRepository,
                                              NotificationService notificationService,
                                              @Value("${app.availability.horizon-reminder-offsets-days:30,7,2,1}") String offsetsCsv) {
        this.scheduleRepository = scheduleRepository;
        this.notificationService = notificationService;
        this.offsetsDaysAscending = Arrays.stream(offsetsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt)
                .sorted().toList();
    }

    @Scheduled(fixedDelayString = "${app.availability.horizon-reminder-processor-delay-ms:86400000}")
    @Transactional
    public void remindExpiringSchedules() {
        for (AvailabilitySchedule schedule : scheduleRepository.findByStatus(ScheduleStatus.ACTIVE)) {
            try {
                remindIfDue(schedule);
            } catch (Exception ex) {
                log.warn("Failed horizon reminder for schedule {}: {}", schedule.getId(), ex.getMessage());
            }
        }
    }

    private void remindIfDue(AvailabilitySchedule schedule) {
        ZoneId zone = safeZone(schedule.getTimezone());
        long daysUntilEnd = ChronoUnit.DAYS.between(LocalDate.now(zone), schedule.getEndDate());
        if (daysUntilEnd < 0) {
            return;
        }

        // Tightest applicable bucket (e.g. 5 days left -> the "7" reminder), one per run.
        Integer bucket = null;
        for (int offset : offsetsDaysAscending) {
            if (daysUntilEnd <= offset) {
                bucket = offset;
                break;
            }
        }
        if (bucket == null) {
            return;
        }

        User host = schedule.getLocalProfile() != null ? schedule.getLocalProfile().getUser() : null;
        if (host == null) {
            return;
        }
        String title = schedule.getExperience() != null ? schedule.getExperience().getTitle() : "your experience";
        String subject = "Your availability for \"" + title + "\" is ending soon";
        String message = "Your recurring availability for \"" + title + "\" ends on " + schedule.getEndDate()
                + " (in " + daysUntilEnd + " day(s)). Extend it so guests can keep booking.";

        notificationService.createEmailAndInAppNotificationForUser(
                host, NotificationType.AVAILABILITY_ENDING_REMINDER, subject, message,
                "AVAILABILITY_SCHEDULE", schedule.getId(),
                "SCHED_ENDING:" + schedule.getId() + ":" + bucket);
    }

    private ZoneId safeZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ex) {
            return FALLBACK_ZONE;
        }
    }
}
