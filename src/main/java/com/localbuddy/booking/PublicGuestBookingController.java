package com.localbuddy.booking;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/guest-bookings")
public class PublicGuestBookingController {

    private final BookingService bookingService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicGuestBookingController(BookingService bookingService,
                                        RateLimitService rateLimitService,
                                        ClientIpResolver clientIpResolver) {
        this.bookingService = bookingService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createGuestBooking(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateGuestBookingRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);

        rateLimitService.checkPublicApiLimit("guest-booking:" + clientIp);

        BookingResponse response = bookingService.createGuestBooking(
                request,
                clientIp,
                servletRequest.getHeader("User-Agent")
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/lookup")
    public ResponseEntity<BookingResponse> lookupGuestBooking(
            HttpServletRequest servletRequest,
            @Valid @RequestBody GuestBookingLookupRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("guest-booking-lookup:" + clientIp);

        return ResponseEntity.ok(bookingService.lookupGuestBooking(request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<BookingResponse> cancelGuestBooking(
            HttpServletRequest servletRequest,
            @Valid @RequestBody GuestCancelBookingRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("guest-booking-cancel:" + clientIp);

        return ResponseEntity.ok(bookingService.cancelGuestBooking(
                request.bookingReference(), request.guestEmail(), request.reason()));
    }

    @PostMapping("/reschedule")
    public ResponseEntity<BookingResponse> rescheduleGuestBooking(
            HttpServletRequest servletRequest,
            @Valid @RequestBody GuestRescheduleBookingRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("guest-booking-reschedule:" + clientIp);

        return ResponseEntity.ok(bookingService.rescheduleGuestBooking(
                request.bookingReference(),
                request.guestEmail(),
                new RescheduleBookingRequest(request.newAvailabilitySlotId(), request.reason())));
    }
}