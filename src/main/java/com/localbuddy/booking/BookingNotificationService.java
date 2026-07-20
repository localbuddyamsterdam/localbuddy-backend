package com.localbuddy.booking;

import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.notification.email.EmailTemplateService;
import com.localbuddy.payment.PaymentGroup;
import com.localbuddy.payment.PaymentGroupMemberResponse;
import com.localbuddy.payment.PaymentGroupRepository;
import com.localbuddy.payment.PaymentGroupResponse;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingNotificationService {

    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EmailTemplateService emailTemplateService;
    private final PaymentGroupRepository paymentGroupRepository;

    public BookingNotificationService(
            NotificationService notificationService,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            EmailTemplateService emailTemplateService,
            PaymentGroupRepository paymentGroupRepository
    ) {
        this.notificationService = notificationService;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.emailTemplateService = emailTemplateService;
        this.paymentGroupRepository = paymentGroupRepository;
    }

    @Transactional
    public void createBookingCreatedNotifications(UUID bookingId, boolean notifyTraveler) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElse(null);

        if (booking == null) {
            return;
        }

        notificationService.createEmailNotificationForUser(
                booking.getLocalProfile().getUser(),
                NotificationType.BOOKING_CREATED,
                "New booking created",
                "A traveler started a booking for your availability slot. Reference: "
                        + booking.getBookingReference()
                        + ". The booking will be confirmed after payment.",
                "BOOKING",
                booking.getId(),
                "BOOKING_CREATED:LOCAL:" + booking.getId()
        );

        // Bundle checkouts (trip-plan) suppress this per-booking traveler/guest copy — the
        // caller sends one combined "complete payment" email covering every item instead,
        // once the whole bundle's payment group exists (see createBundleBookingCreatedNotification).
        if (!notifyTraveler) {
            return;
        }

        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_CREATED,
                    "Complete payment to confirm your booking",
                    "Your booking has been created. Complete payment to confirm it. Reference: "
                            + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_CREATED:TRAVELER:" + booking.getId()
            );
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.GUEST_BOOKING_CREATED,
                    "Complete payment to confirm your guest booking",
                    "Your guest booking has been created. Complete payment to confirm it. Reference: "
                            + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "GUEST_BOOKING_CREATED:" + booking.getId() + ":" + booking.getGuestEmail()
            );
        }
    }

    /**
     * One combined "complete payment" email for an entire trip-plan bundle checkout, sent after
     * every selected item has been booked and the single payment group covering all of them exists.
     * Replaces what would otherwise be one traveler/guest email per booking (see the
     * {@code notifyTraveler=false} bookings created by the bundle checkout loop).
     */
    @Transactional
    public void createBundleBookingCreatedNotification(
            UUID travelerUserId, String guestEmail, String guestPhone, PaymentGroupResponse paymentGroup
    ) {
        int itemCount = paymentGroup.bookings().size();
        String itemWord = itemCount == 1 ? "experience" : "experiences";
        String itemLines = paymentGroup.bookings().stream()
                .map(PaymentGroupMemberResponse::experienceTitle)
                .map(title -> "• " + title)
                .collect(Collectors.joining("\n"));
        String totalText = paymentGroup.currency() + " " + paymentGroup.totalAmount();

        String subject = "Complete payment for your trip (" + itemCount + " " + itemWord + ")";
        String intro = "Your trip includes " + itemCount + " " + itemWord + ". Complete payment to confirm "
                + (itemCount == 1 ? "it" : "all of them") + ":\n\n" + itemLines;
        String message = intro + "\n\nTotal: " + totalText;
        String html = emailTemplateService.renderActionEmail(new EmailTemplateService.ActionEmailModel(
                subject,
                intro,
                "Complete payment",
                paymentGroup.checkoutUrl(),
                "Total: " + totalText));
        String dedupeKey = "BOOKING_CREATED:GROUP:" + paymentGroup.groupToken();
        // Resolved so the processor can re-check the group's live status right before sending —
        // see NotificationProcessingService — and skip a stale nudge for a bundle already paid.
        UUID groupId = paymentGroupRepository.findByGroupToken(paymentGroup.groupToken())
                .map(PaymentGroup::getId)
                .orElse(null);

        if (travelerUserId != null) {
            User user = userRepository.findById(travelerUserId).orElse(null);
            if (user != null) {
                notificationService.createEmailNotificationForUser(
                        user, NotificationType.BOOKING_CREATED, subject, message, html,
                        "PAYMENT_GROUP", groupId, dedupeKey);
            }
        } else {
            notificationService.createEmailNotificationForGuest(
                    guestEmail, guestPhone, NotificationType.GUEST_BOOKING_CREATED, subject, message, html,
                    "PAYMENT_GROUP", groupId, dedupeKey);
        }
    }
}