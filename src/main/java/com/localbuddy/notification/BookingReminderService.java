package com.localbuddy.notification;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingPartySummary;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.payment.HostPayoutText;
import com.localbuddy.user.User;
import com.localbuddy.whatsapp.WhatsAppMapsLink;
import com.localbuddy.whatsapp.WhatsAppTemplates;
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
    private final WhatsAppTemplates whatsAppTemplates;
    private final HostPayoutText hostPayoutText;
    private final int leadHours;

    public BookingReminderService(BookingReminderRepository bookingReminderRepository,
                                  NotificationService notificationService,
                                  NotificationPreferenceService preferenceService,
                                  WhatsAppTemplates whatsAppTemplates,
                                  HostPayoutText hostPayoutText,
                                  @Value("${app.notifications.reminder-lead-hours:24}") int leadHours) {
        this.bookingReminderRepository = bookingReminderRepository;
        this.notificationService = notificationService;
        this.preferenceService = preferenceService;
        this.whatsAppTemplates = whatsAppTemplates;
        this.hostPayoutText = hostPayoutText;
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

        // Independent of the traveler's own reminder preference below — a host who opted in
        // should still get their reminder even if the traveler turned theirs off.
        sendHostReminder(booking, experienceTitle, reference, startTime);

        User traveler = booking.getLoggedInUser();
        if (traveler != null) {
            if (!preferenceService.isBookingRemindersEnabled(traveler.getId())) {
                return;
            }
            notificationService.createEmailAndInAppNotificationForUser(
                    traveler, NotificationType.BOOKING_REMINDER, subject, message,
                    "BOOKING", booking.getId(), dedupeBase);
            // WhatsApp only with the booking's explicit checkout consent; template when
            // configured (deliverable business-initiated), plain text otherwise (in-session only).
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForUser(
                        traveler, NotificationType.BOOKING_REMINDER, subject, message,
                        whatsAppTemplates.forType(NotificationType.BOOKING_REMINDER).orElse(null),
                        reminderWaParams(booking, experienceTitle, reference, startTime),
                        reminderWaButtonParams(booking, reference),
                        "BOOKING", booking.getId(), dedupeBase + ":WHATSAPP");
            }
        } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.BOOKING_REMINDER, subject, message,
                    "BOOKING", booking.getId(), dedupeBase + ":EMAIL");
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForGuest(
                        booking.getGuestEmail(), booking.getGuestPhone(),
                        NotificationType.BOOKING_REMINDER, subject, message,
                        whatsAppTemplates.forType(NotificationType.BOOKING_REMINDER).orElse(null),
                        reminderWaParams(booking, experienceTitle, reference, startTime),
                        reminderWaButtonParams(booking, reference),
                        "BOOKING", booking.getId(), dedupeBase + ":WHATSAPP");
            }
        }
    }

    /**
     * Host-facing reminder (WhatsApp, opt-in via the host's profile) — no host reminder exists in
     * any channel today, so this is a wholly new notification, not an added channel on an existing
     * one. Same "no account phone → fall back to onboarding phone" handling as the new-booking
     * notification in BookingConfirmationNotifier.
     */
    private void sendHostReminder(Booking booking, String title, String reference, Instant startTime) {
        LocalProfile localProfile = booking.getLocalProfile();
        if (localProfile == null || !localProfile.isWhatsappOptIn()) {
            return;
        }
        User host = localProfile.getUser();
        String phone = notBlank(host.getPhone()) ? host.getPhone() : localProfile.getPhoneNumber();
        if (host.getEmail() == null || !notBlank(phone)) {
            return;
        }

        String travelerName = booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getFullName() : booking.getGuestName();
        if (travelerName == null || travelerName.isBlank()) {
            travelerName = "A traveler";
        }
        String when = WHEN_FORMAT.format(startTime);
        String party = BookingPartySummary.describe(booking);
        String payout = hostPayoutText.forBooking(booking);

        String subject = "Reminder: upcoming booking — " + title;
        String message = travelerName + "'s booking for \"" + title + "\" (" + party + ") is coming up "
                + when + ". Reference: " + reference + ".";
        String dedupe = "HOST_BOOKING_REMINDER:" + booking.getId();

        notificationService.createInAppNotificationForUser(
                host, NotificationType.HOST_BOOKING_REMINDER, subject, message,
                "BOOKING", booking.getId(), dedupe + ":INAPP");
        notificationService.createWhatsAppTemplateNotificationForGuest(
                host.getEmail(), phone,
                NotificationType.HOST_BOOKING_REMINDER, subject, message,
                whatsAppTemplates.forType(NotificationType.HOST_BOOKING_REMINDER).orElse(null),
                List.of(firstName(host.getFullName()), travelerName, title, when, party, payout, reference),
                null,
                "BOOKING", booking.getId(), dedupe + ":WHATSAPP");
    }

    private String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** {{1}} first name, {{2}} experience title, {{3}} date & time, {{4}} meeting area, {{5}} reference. */
    private List<String> reminderWaParams(Booking booking, String title, String reference, Instant startTime) {
        String fullName = booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getFullName() : booking.getGuestName();
        String name = fullName == null || fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
        String meeting = booking.getExperience().getMeetingArea() != null
                && !booking.getExperience().getMeetingArea().isBlank()
                ? booking.getExperience().getMeetingArea() : "Shared before the day";
        return List.of(name, title, WHEN_FORMAT.format(startTime), meeting, reference);
    }

    /** Same "Manage my booking" + "Open in Maps" button pair as the booking-confirmed template. */
    private List<String> reminderWaButtonParams(Booking booking, String reference) {
        return List.of(reference, WhatsAppMapsLink.querySuffix(booking.getExperience()));
    }
}
