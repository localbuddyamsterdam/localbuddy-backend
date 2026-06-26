package com.localbuddy.attendance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Logged-in traveler geo check-in for their own booking. */
@RestController
@RequestMapping("/api/bookings")
public class BookingCheckInController {

    private final AttendanceService attendanceService;

    public BookingCheckInController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/{bookingId}/check-in")
    public ResponseEntity<CheckInResponse> checkIn(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CheckInRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(attendanceService.guestCheckIn(userId, bookingId, request));
    }
}
