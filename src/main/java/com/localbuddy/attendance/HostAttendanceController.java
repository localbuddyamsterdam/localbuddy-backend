package com.localbuddy.attendance;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

/** Host-side attendance: arrival check-in (with optional live photo), the guest roster, and marking show / no-show. */
@RestController
@RequestMapping("/api/host")
public class HostAttendanceController {

    private final AttendanceService attendanceService;

    public HostAttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /** Host arrival check-in. Multipart so an optional live photo can ride along with the coordinates. */
    @PostMapping(value = "/slots/{slotId}/check-in", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CheckInResponse> hostCheckIn(
            Authentication authentication,
            @PathVariable UUID slotId,
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam(value = "accuracyMeters", required = false) Double accuracyMeters,
            @RequestParam(value = "photo", required = false) MultipartFile photo
    ) throws IOException {
        UUID userId = UUID.fromString(authentication.getName());
        CheckInRequest request = new CheckInRequest(latitude, longitude, accuracyMeters);
        byte[] photoData = (photo != null && !photo.isEmpty()) ? photo.getBytes() : null;
        String contentType = photo != null ? photo.getContentType() : null;
        String filename = photo != null ? photo.getOriginalFilename() : null;
        return ResponseEntity.ok(
                attendanceService.hostCheckIn(userId, slotId, request, photoData, contentType, filename));
    }

    /** The host's attendance roster for a slot: their own arrival state + each confirmed booking. */
    @GetMapping("/slots/{slotId}/attendance")
    public ResponseEntity<SlotAttendanceResponse> slotAttendance(
            Authentication authentication,
            @PathVariable UUID slotId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(attendanceService.getSlotAttendance(userId, slotId));
    }

    /** Host marks a booking's guest(s) as shown / no-show. */
    @PostMapping("/bookings/{bookingId}/attendance")
    public ResponseEntity<BookingAttendanceRow> markAttendance(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @Valid @RequestBody MarkAttendanceRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(attendanceService.markGuestAttendance(userId, bookingId, request));
    }
}
