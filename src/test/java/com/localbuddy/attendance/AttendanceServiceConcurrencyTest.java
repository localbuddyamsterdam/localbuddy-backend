package com.localbuddy.attendance;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.media.MediaStorageProvider;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the concurrent double-submit hardening: when two requests race to create the first check-in for a
 * booking (guest) or slot (host), the loser trips the V23 partial unique index. Rather than surfacing a
 * 500, the service catches the {@link DataIntegrityViolationException} from the isolated insert and
 * recovers idempotently by re-reading the winning row.
 */
class AttendanceServiceConcurrencyTest {

    private static final BigDecimal LAT = new BigDecimal("52.370216");
    private static final BigDecimal LNG = new BigDecimal("4.895168");

    private final AttendanceCheckInRepository checkInRepository = mock(AttendanceCheckInRepository.class);
    private final AttendanceCheckInWriter checkInWriter = mock(AttendanceCheckInWriter.class);
    private final AvailabilitySlotRepository slotRepository = mock(AvailabilitySlotRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final LocalProfileRepository localProfileRepository = mock(LocalProfileRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final MediaStorageProvider storageProvider = mock(MediaStorageProvider.class);

    // 15 min before/after, 300 m geofence, 500 m worst trusted accuracy, 90-day retention.
    private final CheckInProperties props = new CheckInProperties(15, 15, 300, 500, 90, 86_400_000L);

    private final AttendanceService service = new AttendanceService(
            checkInRepository, checkInWriter, slotRepository, bookingRepository,
            localProfileRepository, userRepository, notificationService, storageProvider, props);

    // ---------------------------------------------------------------------------------------------
    // Guest
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Guest double-submit: insert race is recovered by re-reading the winning row")
    void guestCheckInRecoversFromConcurrentInsert() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        Booking booking = confirmedBooking(bookingId, user);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AttendanceCheckIn winner = new AttendanceCheckIn();
        winner.setRole(CheckInRole.GUEST);
        winner.setCheckedInAt(Instant.parse("2026-06-26T10:00:00Z"));
        winner.setWithinGeofence(true);
        winner.setDistanceMeters(0.0);

        // First read finds nothing (so we try to insert); the recovery re-read finds the winner's row.
        when(checkInRepository.findByBookingIdAndRole(bookingId, CheckInRole.GUEST))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(checkInWriter.insertNew(any()))
                .thenThrow(new DataIntegrityViolationException("uq_attendance_guest_per_booking"));

        CheckInResponse response = service.guestCheckIn(userId, bookingId, geo());

        assertNotNull(response);
        assertEquals(CheckInRole.GUEST, response.role());
        assertTrue(response.withinGeofence());
        assertEquals(winner.getCheckedInAt(), response.checkedInAt());
        assertEquals(300, response.geofenceRadiusMeters());
        verify(checkInWriter, times(1)).insertNew(any());
        verify(checkInRepository, times(2)).findByBookingIdAndRole(bookingId, CheckInRole.GUEST);
    }

    @Test
    @DisplayName("Guest re-check-in: an existing row is updated directly, never routed through the isolated insert")
    void guestCheckInUpdatesExistingRowWithoutIsolatedInsert() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        Booking booking = confirmedBooking(bookingId, user);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AttendanceCheckIn existing = new AttendanceCheckIn();
        existing.setId(UUID.randomUUID());
        existing.setRole(CheckInRole.GUEST);
        when(checkInRepository.findByBookingIdAndRole(bookingId, CheckInRole.GUEST))
                .thenReturn(Optional.of(existing));
        when(checkInRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CheckInResponse response = service.guestCheckIn(userId, bookingId, geo());

        assertNotNull(response);
        assertEquals(CheckInRole.GUEST, response.role());
        verify(checkInRepository, times(1)).save(existing);
        verify(checkInWriter, never()).insertNew(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Host
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Host double-submit: insert race is recovered by re-reading the winning row")
    void hostCheckInRecoversFromConcurrentInsert() {
        UUID userId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        LocalProfile host = mock(LocalProfile.class);
        when(host.getId()).thenReturn(UUID.randomUUID());

        Experience experience = mock(Experience.class);
        when(experience.getLatitude()).thenReturn(LAT);
        when(experience.getLongitude()).thenReturn(LNG);
        when(experience.getLocalProfile()).thenReturn(host);

        AvailabilitySlot slot = mock(AvailabilitySlot.class);
        when(slot.getExperience()).thenReturn(experience);
        when(slot.getStartTime()).thenReturn(Instant.now());

        when(localProfileRepository.findByUserId(userId)).thenReturn(Optional.of(host));
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(User.class)));
        when(bookingRepository.findByAvailabilitySlotIdAndStatusIn(any(), any())).thenReturn(List.of());

        AttendanceCheckIn winner = new AttendanceCheckIn();
        winner.setRole(CheckInRole.HOST);
        winner.setCheckedInAt(Instant.parse("2026-06-26T10:05:00Z"));
        winner.setWithinGeofence(true);
        winner.setDistanceMeters(0.0);
        winner.setPhotoUrl("https://cdn.example/host-arrival.jpg");

        when(checkInRepository.findByAvailabilitySlotIdAndRole(slotId, CheckInRole.HOST))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(checkInWriter.insertNew(any()))
                .thenThrow(new DataIntegrityViolationException("uq_attendance_host_per_slot"));

        CheckInResponse response = service.hostCheckIn(userId, slotId, geo(), null, null, null);

        assertNotNull(response);
        assertEquals(CheckInRole.HOST, response.role());
        assertTrue(response.withinGeofence());
        assertEquals(winner.getCheckedInAt(), response.checkedInAt());
        assertEquals("https://cdn.example/host-arrival.jpg", response.photoUrl());
        verify(checkInWriter, times(1)).insertNew(any());
        verify(checkInRepository, times(2)).findByAvailabilitySlotIdAndRole(slotId, CheckInRole.HOST);
        // Recovery still notifies confirmed guests that the host has arrived.
        verify(bookingRepository).findByAvailabilitySlotIdAndStatusIn(any(), eq(List.of(BookingStatus.CONFIRMED)));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private Booking confirmedBooking(UUID bookingId, User loggedInUser) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getLoggedInUser()).thenReturn(loggedInUser);

        AvailabilitySlot slot = mock(AvailabilitySlot.class);
        when(slot.getStartTime()).thenReturn(Instant.now());
        when(booking.getAvailabilitySlot()).thenReturn(slot);

        Experience experience = mock(Experience.class);
        when(experience.getLatitude()).thenReturn(LAT);
        when(experience.getLongitude()).thenReturn(LNG);
        when(booking.getExperience()).thenReturn(experience);
        return booking;
    }

    /** A precise reading at the meeting point — passes the accuracy gate and lands inside the geofence. */
    private CheckInRequest geo() {
        return new CheckInRequest(LAT, LNG, 8.0);
    }
}
