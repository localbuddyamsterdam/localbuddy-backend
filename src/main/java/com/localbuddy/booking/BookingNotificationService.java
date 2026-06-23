package com.localbuddy.booking;

import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookingNotificationService {

    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;

    public BookingNotificationService(
            NotificationService notificationService,
            BookingRepository bookingRepository
    ) {
        this.notificationService = notificationService;
        this.bookingRepository = bookingRepository;
    }

    @Transactional
    public void createBookingCreatedNotifications(UUID bookingId) {
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
}