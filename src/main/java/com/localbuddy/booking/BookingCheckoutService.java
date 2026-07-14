package com.localbuddy.booking;

import com.localbuddy.payment.CreatePaymentRequest;
import com.localbuddy.payment.PaymentCheckoutResponse;
import com.localbuddy.payment.PaymentService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class BookingCheckoutService {

    private final BookingService bookingService;
    private final PaymentService paymentService;

    public BookingCheckoutService(
            BookingService bookingService,
            PaymentService paymentService
    ) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
    }

    public BookingCheckoutResponse createBookingAndCheckout(
            UUID loggedInUserId,
            CreateBookingRequest request
    ) {
        long totalStart = System.currentTimeMillis();

        long bookingStart = System.currentTimeMillis();
        BookingResponse booking = bookingService.createBooking(loggedInUserId, request);
        long bookingMs = System.currentTimeMillis() - bookingStart;

        long checkoutStart = System.currentTimeMillis();
        PaymentCheckoutResponse checkout = paymentService.createCheckout(
                loggedInUserId,
                new CreatePaymentRequest(booking.id(), request.giftCardCode())
        );
        long checkoutMs = System.currentTimeMillis() - checkoutStart;

        long totalMs = System.currentTimeMillis() - totalStart;

        System.out.println("FAST_UX_TIMING bookingMs=" + bookingMs
                + ", checkoutMs=" + checkoutMs
                + ", totalMs=" + totalMs);

        return new BookingCheckoutResponse(booking, checkout);
    }
}
