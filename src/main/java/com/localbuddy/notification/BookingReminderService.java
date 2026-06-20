package com.localbuddy.notification;

import com.localbuddy.booking.Booking;
import com.localbuddy.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Sends a one-time reminder for each confirmed booking once it enters the
 * reminder lead window. Idempotency is guaranteed by the notification
 * dedupe key, so the scheduler can run as often as it likes without spamming.
 */
@Service
public class BookingReminderService {

    private static final DateTimeFormatter WHEN_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final BookingReminderRepository bookingReminderRepository;
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;
    private final int leadHours;

    public BookingReminderService(BookingReminderRepository bookingReminderRepository,
                                  NotificationService notificationService,
                                  NotificationPreferenceService preferenceService,
                                  @Value("${app.notifications.reminder-lead-hours:24}") int leadHours) {
        this.bookingReminderRepository = bookingReminderRepository;
        this.notificationService = notificationService;
        this.preferenceService = preferenceService;
        this.leadHours = leadHours;
    }

    @Scheduled(fixedDelayString = "${app.notifications.reminder-processor-delay-ms:900000}")
    @Transactional
    public void sendUpcomingBookingReminders() {
        Instant now = Instant.now();
        Instant windowEnd = now.plus(leadHours, ChronoUnit.HOURS);

        List<Booking> upcoming = bookingReminderRepository.findConfirmedStartingBetween(now, windowEnd);
        for (Booking booking : upcoming) {
            sendReminder(booking);
        }
    }

    private void sendReminder(Booking booking) {
        Instant startTime = booking.getAvailabilitySlot().getStartTime();
        String experienceTitle = booking.getExperience().getTitle();
        String reference = booking.getBookingReference();

        String subject = "Reminder: your LocalBuddy experience is coming up";
        String message = "This is a friendly reminder for your upcoming experience \"" + experienceTitle + "\""
                + " on " + WHEN_FORMAT.format(startTime) + "."
                + " Booking reference: " + reference + ".";

        String dedupeBase = "booking-reminder:" + booking.getId();

        User traveler = booking.getTravelerUser();
        if (traveler != null) {
            if (!preferenceService.isBookingRemindersEnabled(traveler.getId())) {
                return;
            }
            notificationService.createEmailAndInAppNotificationForUser(
                    traveler, NotificationType.BOOKING_REMINDER, subject, message,
                    "BOOKING", booking.getId(), dedupeBase);
        } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.BOOKING_REMINDER, subject, message,
                    "BOOKING", booking.getId(), dedupeBase + ":EMAIL");
        }
    }
}
