package com.localbuddy.booking;

import com.localbuddy.calendar.CalendarService;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.wallet.AppleWalletService;
import com.localbuddy.wallet.GoogleWalletService;
import com.localbuddy.wallet.WalletPassData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sends the customer-facing "booking confirmed" notification, including one-tap
 * links to add the booking to Google/Apple Wallet and a calendar. Idempotent per
 * booking via the notification dedupe key. Link building is best-effort: a wallet
 * or calendar hiccup never blocks the confirmation message itself.
 */
@Service
public class BookingConfirmationNotifier {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final NotificationService notificationService;
    private final GoogleWalletService googleWalletService;
    private final AppleWalletService appleWalletService;
    private final CalendarService calendarService;
    private final String frontendBaseUrl;

    public BookingConfirmationNotifier(NotificationService notificationService,
                                       GoogleWalletService googleWalletService,
                                       AppleWalletService appleWalletService,
                                       CalendarService calendarService,
                                       @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.notificationService = notificationService;
        this.googleWalletService = googleWalletService;
        this.appleWalletService = appleWalletService;
        this.calendarService = calendarService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public void sendConfirmation(Booking booking) {
        if (booking == null || booking.getBookingReference() == null) {
            return;
        }

        String ref = booking.getBookingReference();
        String title = booking.getExperience() != null && booking.getExperience().getTitle() != null
                ? booking.getExperience().getTitle() : "your experience";

        StringBuilder body = new StringBuilder();
        body.append("Your booking for \"").append(title).append("\" is confirmed. Reference: ").append(ref).append(".");
        if (booking.getAvailabilitySlot() != null && booking.getAvailabilitySlot().getStartTime() != null) {
            body.append(" When: ").append(WHEN.format(booking.getAvailabilitySlot().getStartTime())).append(".");
        }
        appendCustomerLinks(body, booking, ref);

        String subject = "Your booking is confirmed";
        String dedupe = "BOOKING_CONFIRMED:" + booking.getId();
        String message = body.toString();

        User traveler = booking.getTravelerUser();
        if (traveler != null) {
            notificationService.createEmailAndInAppNotificationForUser(
                    traveler, NotificationType.BOOKING_CONFIRMED, subject, message,
                    "BOOKING", booking.getId(), dedupe);
            notificationService.createWhatsAppNotificationForUser(
                    traveler, NotificationType.BOOKING_CONFIRMED, subject, message,
                    "BOOKING", booking.getId(), dedupe + ":WHATSAPP");
        } else if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.BOOKING_CONFIRMED, subject, message,
                    "BOOKING", booking.getId(), dedupe + ":EMAIL");
            notificationService.createWhatsAppNotificationForGuest(
                    booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.BOOKING_CONFIRMED, subject, message,
                    "BOOKING", booking.getId(), dedupe + ":WHATSAPP");
        }
    }

    private void appendCustomerLinks(StringBuilder body, Booking booking, String ref) {
        String bookingPage = frontendBaseUrl + "/bookings/" + ref;
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
