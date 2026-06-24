package com.localbuddy.reminder;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.experience.Experience;
import com.localbuddy.notification.NotificationPreferenceService;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.wishlist.WishlistItem;
import com.localbuddy.wishlist.WishlistItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Nudges for wishlisted-but-not-booked experiences and abandoned (expired-unpaid) bookings, sent at
 * configurable offsets (default 2h / 24h / 48h). "Send each reminder once per stage" is enforced by
 * the notification {@code dedupeKey}; no per-row sent flag is needed. There is no cart, so the
 * abandoned-booking nudge targets EXPIRED bookings and links back to the experience to re-book.
 */
@Service
public class ReminderService {

    /** Statuses meaning the user already engaged with an experience — suppresses wishlist nudges. */
    private static final Set<BookingStatus> ENGAGED = Set.of(
            BookingStatus.REQUESTED, BookingStatus.ACCEPTED, BookingStatus.PENDING_PAYMENT,
            BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

    private final WishlistItemRepository wishlistItemRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;
    private final String frontendBaseUrl;
    private final long[] offsetsHours;
    private final long maxAgeHours;

    public ReminderService(WishlistItemRepository wishlistItemRepository,
                           BookingRepository bookingRepository,
                           NotificationService notificationService,
                           NotificationPreferenceService preferenceService,
                           @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
                           @Value("${app.reminders.offsets-hours:2,24,48}") String offsetsCsv,
                           @Value("${app.reminders.max-age-hours:72}") long maxAgeHours) {
        this.wishlistItemRepository = wishlistItemRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
        this.preferenceService = preferenceService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.offsetsHours = Arrays.stream(offsetsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .mapToLong(Long::parseLong).distinct().sorted().toArray();
        this.maxAgeHours = maxAgeHours;
    }

    @Scheduled(fixedDelayString = "${app.reminders.processor-delay-ms:1800000}")
    @Transactional
    public void sendWishlistReminders() {
        if (offsetsHours.length == 0) {
            return;
        }
        Instant now = Instant.now();
        List<WishlistItem> candidates = wishlistItemRepository
                .findTop200ByCreatedAtBetweenOrderByCreatedAtAsc(windowStart(now), windowEnd(now));

        for (WishlistItem item : candidates) {
            long stage = dueStage(Duration.between(item.getCreatedAt(), now).toHours());
            if (stage < 0) {
                continue;
            }
            User user = item.getUser();
            Experience exp = item.getExperience();
            if (bookingRepository.existsByLoggedInUserIdAndExperienceIdAndStatusIn(user.getId(), exp.getId(), ENGAGED)) {
                continue; // already booked/engaged — stop nudging
            }
            String subject = "Still interested in " + exp.getTitle() + "?";
            String body = "You saved \"" + exp.getTitle() + "\" to your wishlist.\n\nReady to book? "
                    + frontendBaseUrl + "/experiences/" + exp.getSlug();
            String base = "wishlist-reminder:" + item.getId() + ":" + stage + "h";

            notificationService.createInAppNotificationForUser(user, NotificationType.WISHLIST_REMINDER,
                    subject, body, "WISHLIST", item.getId(), base + ":INAPP");
            if (preferenceService.isEmailEnabled(user.getId())) {
                notificationService.createEmailNotificationForUser(user, NotificationType.WISHLIST_REMINDER,
                        subject, body, "WISHLIST", item.getId(), base + ":EMAIL");
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.reminders.processor-delay-ms:1800000}")
    @Transactional
    public void sendAbandonedBookingReminders() {
        if (offsetsHours.length == 0) {
            return;
        }
        Instant now = Instant.now();
        List<Booking> candidates = bookingRepository
                .findTop200ByStatusAndCancelledAtBetweenOrderByCancelledAtAsc(
                        BookingStatus.EXPIRED, windowStart(now), windowEnd(now));

        for (Booking booking : candidates) {
            if (booking.getCancelledAt() == null) {
                continue;
            }
            long stage = dueStage(Duration.between(booking.getCancelledAt(), now).toHours());
            if (stage < 0) {
                continue;
            }
            Experience exp = booking.getExperience();
            String title = exp != null ? exp.getTitle() : "your experience";
            String link = frontendBaseUrl + "/experiences/" + (exp != null ? exp.getSlug() : "");
            String subject = "Finish booking " + title;
            String body = "You started booking \"" + title + "\" but didn't complete payment in time.\n\n"
                    + "Pick up where you left off: " + link;
            String base = "abandoned-booking:" + booking.getId() + ":" + stage + "h";

            if (booking.getLoggedInUser() != null) {
                User user = booking.getLoggedInUser();
                notificationService.createInAppNotificationForUser(user, NotificationType.BOOKING_ABANDONED_REMINDER,
                        subject, body, "BOOKING", booking.getId(), base + ":INAPP");
                if (preferenceService.isEmailEnabled(user.getId())) {
                    notificationService.createEmailNotificationForUser(user, NotificationType.BOOKING_ABANDONED_REMINDER,
                            subject, body, "BOOKING", booking.getId(), base + ":EMAIL");
                }
            } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
                notificationService.createEmailNotificationForGuest(booking.getGuestEmail(), null,
                        NotificationType.BOOKING_ABANDONED_REMINDER, subject, body,
                        "BOOKING", booking.getId(), base + ":EMAIL");
            }
        }
    }

    /** Items at least the smallest offset old (eligible for stage 1). */
    private Instant windowEnd(Instant now) {
        return now.minus(offsetsHours[0], ChronoUnit.HOURS);
    }

    /** Stop scanning items older than the cap so we don't nudge ancient ones forever. */
    private Instant windowStart(Instant now) {
        return now.minus(maxAgeHours, ChronoUnit.HOURS);
    }

    /** Largest configured offset that the age has crossed, or -1 if none. dedupeKey makes it once-per-stage. */
    private long dueStage(long ageHours) {
        long stage = -1;
        for (long offset : offsetsHours) {
            if (ageHours >= offset) {
                stage = offset;
            } else {
                break;
            }
        }
        return stage;
    }
}
