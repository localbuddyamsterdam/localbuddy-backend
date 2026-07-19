package com.localbuddy.booking;

import com.localbuddy.calendar.CalendarService;
import com.localbuddy.experience.Experience;
import com.localbuddy.media.ExperiencePhoto;
import com.localbuddy.media.ExperiencePhotoRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.notification.email.EmailTemplateService;
import com.localbuddy.user.User;
import com.localbuddy.wallet.AppleWalletService;
import com.localbuddy.wallet.GoogleWalletService;
import com.localbuddy.wallet.WalletPassData;
import com.localbuddy.whatsapp.WhatsAppMapsLink;
import com.localbuddy.whatsapp.WhatsAppTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Sends the customer-facing "booking confirmed" notification as branded HTML (with a plain-text
 * fallback), plus WhatsApp/in-app. The hero image is the experience's cover photo when it has one;
 * otherwise the email falls back to a branded band. Idempotent per booking via the dedupe key;
 * wallet/calendar link building is best-effort and never blocks the confirmation.
 */
@Service
public class BookingConfirmationNotifier {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter WHEN_DATE =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter WHEN_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** Category slugs that have a bespoke illustration served at /illustrations/&lt;slug&gt;.png. */
    private static final Set<String> ILLUSTRATED_CATEGORIES = Set.of(
            "food", "photo-walk", "hidden-gems", "local-markets",
            "cafe-hopping", "student-life", "nightlife", "custom");

    private final NotificationService notificationService;
    private final GoogleWalletService googleWalletService;
    private final AppleWalletService appleWalletService;
    private final CalendarService calendarService;
    private final EmailTemplateService emailTemplateService;
    private final ExperiencePhotoRepository experiencePhotoRepository;
    private final WhatsAppTemplates whatsAppTemplates;
    private final String frontendBaseUrl;
    private final String publicBaseUrl;

    public BookingConfirmationNotifier(NotificationService notificationService,
                                       GoogleWalletService googleWalletService,
                                       AppleWalletService appleWalletService,
                                       CalendarService calendarService,
                                       EmailTemplateService emailTemplateService,
                                       ExperiencePhotoRepository experiencePhotoRepository,
                                       WhatsAppTemplates whatsAppTemplates,
                                       @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
                                       @Value("${app.public-base-url:https://localbuddy-backend-b4exhkbjgahme6ge.francecentral-01.azurewebsites.net}") String publicBaseUrl) {
        this.notificationService = notificationService;
        this.googleWalletService = googleWalletService;
        this.appleWalletService = appleWalletService;
        this.calendarService = calendarService;
        this.emailTemplateService = emailTemplateService;
        this.experiencePhotoRepository = experiencePhotoRepository;
        this.whatsAppTemplates = whatsAppTemplates;
        this.frontendBaseUrl = frontendBaseUrl;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public void sendConfirmation(Booking booking) {
        if (booking == null || booking.getBookingReference() == null) {
            return;
        }

        String ref = booking.getBookingReference();
        Experience experience = booking.getExperience();
        String title = experience != null && experience.getTitle() != null
                ? experience.getTitle() : "your experience";

        // Plain-text body — kept as the deliverability/accessibility fallback.
        StringBuilder body = new StringBuilder();
        body.append("Your booking for \"").append(title).append("\" is confirmed. Reference: ").append(ref).append(".");
        if (booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null) {
            body.append(" When: ").append(WHEN.format(booking.getAvailabilitySlot().getStartTime())).append(".");
        }
        appendCustomerLinks(body, booking, ref);

        String subject = "Booking confirmed — " + title;
        if (booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null) {
            subject = subject + " · " + WHEN.format(booking.getAvailabilitySlot().getStartTime());
        }
        String dedupe = "BOOKING_CONFIRMED:" + booking.getId();
        String message = body.toString();
        // HTML is best-effort: a rendering issue must never roll back the booking or block the
        // (plain-text) confirmation. If it fails, html stays null and the email goes as plain text.
        String html = null;
        try {
            html = emailTemplateService.renderBookingConfirmation(buildModel(booking, experience, ref, title));
        } catch (Exception ex) {
            html = null;
        }

        // In-app feed gets a concise, URL-free message — the app links to the
        // booking itself (raw URLs render as noise in the notification list).
        StringBuilder inApp = new StringBuilder();
        inApp.append("Your booking for \"").append(title).append("\" is confirmed. Reference: ").append(ref).append(".");
        if (booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null) {
            inApp.append("\nWhen: ").append(WHEN.format(booking.getAvailabilitySlot().getStartTime())).append(".");
        }

        User traveler = booking.getLoggedInUser();
        if (traveler != null) {
            notificationService.createEmailNotificationForUser(
                    traveler, NotificationType.BOOKING_CONFIRMED, subject, message, html,
                    "BOOKING", booking.getId(), dedupe + ":EMAIL");
            notificationService.createInAppNotificationForUser(
                    traveler, NotificationType.BOOKING_CONFIRMED, subject, inApp.toString(),
                    "BOOKING", booking.getId(), dedupe + ":INAPP");
            // WhatsApp only with explicit checkout consent; template (deliverable business-
            // initiated) when one is configured, plain text otherwise (in-session only).
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForUser(
                        traveler, NotificationType.BOOKING_CONFIRMED, subject, message,
                        whatsAppTemplates.forType(NotificationType.BOOKING_CONFIRMED).orElse(null),
                        confirmationWaParams(booking, title, ref), confirmationWaButtonParams(booking, ref),
                        "BOOKING", booking.getId(), dedupe + ":WHATSAPP");
            }
        } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.BOOKING_CONFIRMED, subject, message, html,
                    "BOOKING", booking.getId(), dedupe + ":EMAIL");
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForGuest(
                        booking.getGuestEmail(), booking.getGuestPhone(),
                        NotificationType.BOOKING_CONFIRMED, subject, message,
                        whatsAppTemplates.forType(NotificationType.BOOKING_CONFIRMED).orElse(null),
                        confirmationWaParams(booking, title, ref), confirmationWaButtonParams(booking, ref),
                        "BOOKING", booking.getId(), dedupe + ":WHATSAPP");
            }
        }
    }

    /**
     * Body params for the booking-confirmed / booking-reminder utility templates:
     * {{1}} first name, {{2}} experience title, {{3}} date &amp; time, {{4}} meeting area, {{5}} reference.
     */
    private java.util.List<String> confirmationWaParams(Booking booking, String title, String ref) {
        String name = firstName(booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getFullName() : booking.getGuestName());
        String when = booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null
                ? WHEN.format(booking.getAvailabilitySlot().getStartTime())
                : "your booked time";
        Experience experience = booking.getExperience();
        String meeting = experience != null && experience.getMeetingArea() != null
                && !experience.getMeetingArea().isBlank()
                ? experience.getMeetingArea() : "Shared before the day";
        return java.util.List.of(name, title, when, meeting, ref);
    }

    /**
     * Dynamic URL button suffixes for the booking-confirmed template, in button-index order:
     * index 0 the booking reference (a "Manage my booking" button linking to {@code /booking/{ref}}),
     * index 1 an encoded Google Maps query for the meeting point (an "Open in Maps" button whose
     * static base — {@code https://www.google.com/maps/search/?api=1&query=} — is configured on
     * the template itself; this works cross-platform, iOS included, without a separate Apple Maps
     * link). A template with a second URL button requires every button's parameter to be present.
     */
    private java.util.List<String> confirmationWaButtonParams(Booking booking, String ref) {
        return java.util.List.of(ref, WhatsAppMapsLink.querySuffix(booking.getExperience()));
    }

    private EmailTemplateService.BookingConfirmationModel buildModel(
            Booking booking, Experience experience, String ref, String title) {

        String greeting = firstName(booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getFullName() : booking.getGuestName());

        String hostName = experience != null && experience.getLocalProfile() != null
                ? experience.getLocalProfile().getDisplayName() : null;
        String hostLine = hostName != null && !hostName.isBlank()
                ? "Hosted by " + hostName : "Your LocalBuddy host";

        String whenDate = "";
        String whenTime = "";
        if (booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null) {
            var start = booking.getAvailabilitySlot().getStartTime();
            whenDate = WHEN_DATE.format(start);
            whenTime = WHEN_TIME.format(start);
        }

        int guests = booking.getGuestsCount() == null ? 1 : booking.getGuestsCount();
        String guestsMain = guests + (guests == 1 ? " guest" : " guests");

        String meetingMain = experience != null && experience.getMeetingArea() != null
                && !experience.getMeetingArea().isBlank()
                ? experience.getMeetingArea() : "Shared after booking";

        String currency = booking.getCurrency() == null ? "EUR" : booking.getCurrency();
        String total = currencySymbol(currency) + formatAmount(booking.getTotalAmount());

        String manageUrl = frontendBaseUrl + "/booking/" + ref;
        String calendarUrl = null;
        try {
            calendarUrl = calendarService.googleCalendarLink(booking);
        } catch (Exception ignored) {
            // best-effort; skip the calendar CTA if the slot has no time
        }

        return new EmailTemplateService.BookingConfirmationModel(
                greeting,
                title,
                hostLine,
                whenDate,
                whenTime,
                guestsMain,
                null,
                meetingMain,
                null,
                total,
                "Paid in full",
                ref,
                manageUrl,
                calendarUrl,
                heroUrl(experience));
    }

    /** The experience's first photo (its cover) or null when it has none. */
    private String coverPhotoUrl(Experience experience) {
        if (experience == null || experience.getId() == null) {
            return null;
        }
        List<ExperiencePhoto> photos =
                experiencePhotoRepository.findByExperienceIdOrderBySortOrderAscCreatedAtAsc(experience.getId());
        return photos.isEmpty() ? null : photos.get(0).getUrl();
    }

    /** Email hero: the experience's real cover photo, else its category illustration (else default). */
    private String heroUrl(Experience experience) {
        String photo = coverPhotoUrl(experience);
        if (photo != null) {
            return photo;
        }
        String slug = null;
        try {
            if (experience != null && experience.getCategory() != null) {
                slug = experience.getCategory().getSlug();
            }
        } catch (Exception ignored) {
            // category not loadable in this context — fall back to the default illustration
        }
        String name = slug != null && ILLUSTRATED_CATEGORIES.contains(slug) ? slug : "default";
        return publicBaseUrl + "/illustrations/" + name + ".png";
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }

    private String currencySymbol(String currency) {
        return switch (currency == null ? "" : currency.toUpperCase()) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            default -> (currency == null ? "" : currency) + " ";
        };
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void appendCustomerLinks(StringBuilder body, Booking booking, String ref) {
        String bookingPage = frontendBaseUrl + "/booking/" + ref;
        body.append("\n\nManage your booking: ").append(bookingPage);

        try {
            if (googleWalletService.isConfigured()) {
                body.append("\nAdd to Google Wallet: ")
                        .append(googleWalletService.buildSaveUrl(WalletPassData.from(booking)));
            }
        } catch (Exception ignored) {
            // best-effort; skip the wallet link if it can't be built
        }

        try {
            body.append("\nAdd to calendar: ").append(calendarService.googleCalendarLink(booking));
        } catch (Exception ignored) {
            // best-effort; skip the calendar link if the slot has no time
        }

        if (appleWalletService.isConfigured()) {
            body.append("\nAdd to Apple Wallet: open ").append(bookingPage).append(" on your iPhone");
        }
    }
}
