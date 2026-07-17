package com.localbuddy.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingCheckoutService bookingCheckoutService;
    private final BookingAuditService bookingAuditService;

    public BookingController(BookingService bookingService,
                            BookingCheckoutService bookingCheckoutService,
                            BookingAuditService bookingAuditService) {
        this.bookingService = bookingService;
        this.bookingCheckoutService = bookingCheckoutService;
        this.bookingAuditService = bookingAuditService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            Authentication authentication,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        BookingResponse response = bookingService.createBooking(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<BookingResponse>> getMyBookings(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.getMyBookings(userId));
    }

    @PostMapping("/{bookingId}/accept")
    public ResponseEntity<BookingResponse> acceptBooking(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody BookingDecisionRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.acceptBooking(userId, bookingId, request));
    }

    @PostMapping("/{bookingId}/decline")
    public ResponseEntity<BookingResponse> declineBooking(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody BookingDecisionRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.declineBooking(userId, bookingId, request));
    }

    @PostMapping("/{bookingId}/cancel-by-traveler")
    public ResponseEntity<BookingResponse> cancelBookingByLoggedInUser(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.cancelBookingByLoggedInUser(userId, bookingId, request));
    }

    /** Refund + cancellation-fee the traveller would incur cancelling this booking (for the confirm popup). */
    @GetMapping("/{bookingId}/refund-preview")
    public ResponseEntity<RefundPreviewResponse> getRefundPreview(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.getTravelerRefundPreview(userId, bookingId));
    }

    /** Change timeline for a booking (created / rescheduled / cancelled / …). Owner-scoped: the
     *  ownership check throws 404 for anyone who can't already read this booking. */
    @GetMapping("/{bookingId}/history")
    public ResponseEntity<List<BookingAuditResponse>> getBookingHistory(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        bookingService.getBookingById(userId, bookingId); // authz gate (throws if not permitted)
        return ResponseEntity.ok(bookingAuditService.getEvents(bookingId));
    }

    @PostMapping("/{bookingId}/cancel-by-local")
    public ResponseEntity<BookingResponse> cancelBookingByLocal(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.cancelBookingByLocal(userId, bookingId, request));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBookingById(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.getBookingById(userId, bookingId));
    }

    @PostMapping("/{bookingId}/complete")
    public ResponseEntity<BookingResponse> completeBooking(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(bookingService.completeBooking(userId, bookingId));
    }

    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<BookingResponse> rescheduleBookingByLocal(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request
    ) {
        UUID localUserId = UUID.fromString(authentication.getName());

        return ResponseEntity.ok(
                bookingService.rescheduleBookingByLocal(localUserId, bookingId, request)
        );
    }

    @PostMapping("/checkout")
    public ResponseEntity<BookingCheckoutResponse> createBookingAndCheckout(
            Authentication authentication,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        UUID loggedInUserId = UUID.fromString(authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingCheckoutService.createBookingAndCheckout(loggedInUserId, request));
    }
}