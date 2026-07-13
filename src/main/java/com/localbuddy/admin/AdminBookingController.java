package com.localbuddy.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {

    private final BookingService bookingService;
    private final BookingAuditService bookingAuditService;

    public AdminBookingController(BookingService bookingService, BookingAuditService bookingAuditService) {
        this.bookingService = bookingService;
        this.bookingAuditService = bookingAuditService;
    }

    /** The acting admin's user id — the JWT principal is the user UUID (see JwtAuthenticationFilter). */
    private static UUID adminId(Authentication authentication) {
        return authentication != null ? UUID.fromString(authentication.getName()) : null;
    }

    @GetMapping
    public ResponseEntity<List<BookingResponse>> getAdminBookings(
            @RequestParam(required = false) BookingStatus status
    ) {
        return ResponseEntity.ok(bookingService.getAdminBookings(status));
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBookingByAdmin(
            Authentication authentication,
            @Valid @RequestBody AdminCreateBookingRequest request
    ) {
        BookingResponse created = bookingService.createBookingByAdmin(request);
        bookingAuditService.record(created.id(), "CREATE", "Created booking on behalf", adminId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getAdminBookingById(
            @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(bookingService.getAdminBookingById(bookingId));
    }

    @GetMapping("/{bookingId}/events")
    public ResponseEntity<List<BookingAuditResponse>> getBookingEvents(
            @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(bookingAuditService.getEvents(bookingId));
    }

    @PutMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> updateBookingDetailsByAdmin(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminUpdateBookingRequest request
    ) {
        BookingResponse updated = bookingService.updateBookingDetailsByAdmin(bookingId, request);
        bookingAuditService.record(bookingId, "EDIT_DETAILS", "Edited contact & notes", adminId(authentication));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancelBookingByAdmin(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminCancelBookingRequest request
    ) {
        BookingResponse updated = bookingService.cancelBookingByAdmin(bookingId, request);
        String refundNote = request.refundAmount() != null ? "refund €" + request.refundAmount()
                : request.refundPercentage() != null ? "refund " + request.refundPercentage() + "%"
                : "refund per policy";
        bookingAuditService.record(bookingId, "CANCEL", "Cancelled (" + refundNote + ")", adminId(authentication));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{bookingId}/attendance")
    public ResponseEntity<BookingResponse> setBookingAttendanceByAdmin(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminSetAttendanceRequest request
    ) {
        BookingResponse updated = bookingService.setBookingAttendanceByAdmin(bookingId, request);
        bookingAuditService.record(bookingId, "ATTENDANCE", "Attendance set to " + request.outcome(), adminId(authentication));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{bookingId}/resend-confirmation")
    public ResponseEntity<BookingResponse> resendBookingConfirmationByAdmin(
            @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(
                bookingService.resendBookingConfirmationByAdmin(bookingId)
        );
    }

    @PostMapping("/{bookingId}/complete")
    public ResponseEntity<BookingResponse> completeBookingByAdmin(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        BookingResponse updated = bookingService.completeBookingByAdmin(bookingId);
        bookingAuditService.record(bookingId, "COMPLETE", "Marked completed", adminId(authentication));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<BookingResponse> rescheduleBookingByAdmin(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request
    ) {
        BookingResponse updated = bookingService.rescheduleBookingByAdmin(bookingId, request);
        String when = updated.slotStartTime() != null ? " to " + updated.slotStartTime() : "";
        bookingAuditService.record(bookingId, "RESCHEDULE", "Rescheduled" + when, adminId(authentication));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{bookingId}/party")
    public ResponseEntity<BookingResponse> updateBookingParty(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminUpdateBookingPartyRequest request
    ) {
        BookingResponse updated = bookingService.updateBookingPartyByAdmin(bookingId, request);
        bookingAuditService.record(bookingId, "PARTY", "Party set to " + updated.guestsCount() + " guests", adminId(authentication));
        return ResponseEntity.ok(updated);
    }
}
