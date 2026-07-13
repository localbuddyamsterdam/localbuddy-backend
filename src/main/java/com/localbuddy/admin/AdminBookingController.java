package com.localbuddy.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public ResponseEntity<List<BookingResponse>> getAdminBookings(
            @RequestParam(required = false) BookingStatus status
    ) {
        return ResponseEntity.ok(bookingService.getAdminBookings(status));
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBookingByAdmin(
            @Valid @RequestBody AdminCreateBookingRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBookingByAdmin(request));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getAdminBookingById(
            @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(bookingService.getAdminBookingById(bookingId));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancelBookingByAdmin(
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest request
    ) {
        return ResponseEntity.ok(
                bookingService.cancelBookingByAdmin(bookingId, request)
        );
    }

    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<BookingResponse> rescheduleBookingByAdmin(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request
    ) {
        return ResponseEntity.ok(
                bookingService.rescheduleBookingByAdmin(bookingId, request)
        );
    }

    @PostMapping("/{bookingId}/party")
    public ResponseEntity<BookingResponse> updateBookingParty(
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminUpdateBookingPartyRequest request
    ) {
        return ResponseEntity.ok(
                bookingService.updateBookingPartyByAdmin(bookingId, request)
        );
    }
}