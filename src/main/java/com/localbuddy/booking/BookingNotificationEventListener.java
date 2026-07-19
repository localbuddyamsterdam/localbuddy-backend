package com.localbuddy.booking;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BookingNotificationEventListener {

    private final BookingNotificationService bookingNotificationService;

    public BookingNotificationEventListener(
            BookingNotificationService bookingNotificationService
    ) {
        this.bookingNotificationService = bookingNotificationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCreated(BookingCreatedEvent event) {
        bookingNotificationService.createBookingCreatedNotifications(event.bookingId(), event.notifyTraveler());
    }
}