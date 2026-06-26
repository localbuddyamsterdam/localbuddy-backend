package com.localbuddy.attendance;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingSource;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.booking.GuestShowStatus;
import com.localbuddy.common.GeoUtil;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.media.MediaStorageProvider;
import com.localbuddy.media.StoredObject;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Geo check-in / arrival attendance.
 *
 * <p>Guests are hard-gated: they may only check in when inside the geofence (GPS error accounted for)
 * within the time window. Hosts are never rejected for distance — their check-in always records, with
 * a warning and the measured distance when they are outside the geofence. The host can also mark each
 * booking's guest(s) as shown / no-show once they meet in person. This is operational signal, not an
 * auto-refund decision.
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    private final AttendanceCheckInRepository checkInRepository;
    private final AttendanceCheckInWriter checkInWriter;
    private final AvailabilitySlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final LocalProfileRepository localProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MediaStorageProvider storageProvider;
    private final CheckInProperties props;

    public AttendanceService(AttendanceCheckInRepository checkInRepository,
                             AttendanceCheckInWriter checkInWriter,
                             AvailabilitySlotRepository slotRepository,
                             BookingRepository bookingRepository,
                             LocalProfileRepository localProfileRepository,
                             UserRepository userRepository,
                             NotificationService notificationService,
                             MediaStorageProvider storageProvider,
                             CheckInProperties props) {
        this.checkInRepository = checkInRepository;
        this.checkInWriter = checkInWriter;
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.localProfileRepository = localProfileRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.storageProvider = storageProvider;
        this.props = props;
    }

    // ---------------------------------------------------------------------------------------------
    // Guest check-in
    // ---------------------------------------------------------------------------------------------

    /** Logged-in traveler checks in to their own booking. */
    @Transactional
    public CheckInResponse guestCheckIn(UUID userId, UUID bookingId, CheckInRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (booking.getLoggedInUser() == null || !booking.getLoggedInUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return doGuestCheckIn(booking, user, booking.getGuestEmail(), request);
    }

    /** Anonymous guest checks in via booking reference + email (same gate as guest booking lookup). */
    @Transactional
    public CheckInResponse guestCheckInAnonymous(GuestCheckInRequest request) {
        String reference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String email = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));
        if (booking.getBookingSource() != BookingSource.GUEST_USER
                || booking.getGuestEmail() == null
                || !booking.getGuestEmail().equalsIgnoreCase(email)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        CheckInRequest geo = new CheckInRequest(
                request.latitude(), request.longitude(), request.accuracyMeters());
        return doGuestCheckIn(booking, null, booking.getGuestEmail(), geo);
    }

    private CheckInResponse doGuestCheckIn(Booking booking, User user, String guestEmail, CheckInRequest geo) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Check-in is only available for confirmed bookings.");
        }
        AvailabilitySlot slot = booking.getAvailabilitySlot();
        Experience experience = booking.getExperience();
        enforceWindow(slot);

        // Guests must supply a trustworthy accuracy reading.
        if (geo.accuracyMeters() == null) {
            throw new BadRequestException(
                    "We couldn't read your location accuracy. Enable precise location and try again.");
        }
        if (geo.accuracyMeters() > props.maxAccuracyMeters()) {
            throw new BadRequestException("Your location is too imprecise (±"
                    + Math.round(geo.accuracyMeters()) + " m). Move to an open area and try again.");
        }

        GeoResult result = evaluateGeofence(experience, geo.latitude(), geo.longitude(), geo.accuracyMeters());
        if (!result.coordinateSet()) {
            throw new BadRequestException(
                    "Check-in isn't available for this experience yet — no meeting point has been set.");
        }
        if (!result.withinGeofence()) {
            throw new BadRequestException("You're about " + Math.round(result.distanceMeters())
                    + " m away. You can check in once you're within "
                    + Math.round(props.geofenceRadiusMeters()) + " m of the meeting point.");
        }

        AttendanceCheckIn checkIn = checkInRepository
                .findByBookingIdAndRole(booking.getId(), CheckInRole.GUEST)
                .orElseGet(AttendanceCheckIn::new);
        checkIn.setAvailabilitySlot(slot);
        checkIn.setBooking(booking);
        checkIn.setUser(user);
        checkIn.setGuestEmail(user == null ? guestEmail : null);
        checkIn.setRole(CheckInRole.GUEST);
        checkIn.setCheckedInAt(Instant.now());
        applyGeo(checkIn, geo, result);
        checkIn = saveHandlingConcurrentInsert(checkIn,
                () -> checkInRepository.findByBookingIdAndRole(booking.getId(), CheckInRole.GUEST));

        return new CheckInResponse(
                CheckInRole.GUEST,
                checkIn.getCheckedInAt(),
                true,
                result.distanceMeters(),
                props.geofenceRadiusMeters(),
                null,
                null);
    }

    // ---------------------------------------------------------------------------------------------
    // Host check-in (soft) + arrival photo
    // ---------------------------------------------------------------------------------------------

    /**
     * Host checks in for a slot. Never rejected for distance; outside the geofence we still record and
     * return a warning + the measured distance. Optional live arrival photo. Notifies confirmed guests.
     */
    @Transactional
    public CheckInResponse hostCheckIn(UUID userId, UUID slotId, CheckInRequest request,
                                       byte[] photoData, String photoContentType, String photoFilename) {
        AvailabilitySlot slot = requireSlotOwnedByHost(userId, slotId);
        Experience experience = slot.getExperience();
        enforceWindow(slot);

        GeoResult result = evaluateGeofence(experience, request.latitude(), request.longitude(),
                request.accuracyMeters());

        AttendanceCheckIn checkIn = checkInRepository
                .findByAvailabilitySlotIdAndRole(slotId, CheckInRole.HOST)
                .orElseGet(AttendanceCheckIn::new);
        checkIn.setAvailabilitySlot(slot);
        checkIn.setBooking(null);
        checkIn.setUser(userRepository.findById(userId).orElse(null));
        checkIn.setGuestEmail(null);
        checkIn.setRole(CheckInRole.HOST);
        checkIn.setCheckedInAt(Instant.now());
        applyGeo(checkIn, request, result);

        if (photoData != null && photoData.length > 0 && storageProvider.isConfigured()) {
            StoredObject stored = storageProvider.upload(photoData, photoContentType,
                    photoFilename != null ? photoFilename : "host-arrival.jpg");
            checkIn.setPhotoUrl(stored.url());
            checkIn.setPhotoStorageKey(stored.storageKey());
        }

        checkIn = saveHandlingConcurrentInsert(checkIn,
                () -> checkInRepository.findByAvailabilitySlotIdAndRole(slotId, CheckInRole.HOST));
        notifyGuestsHostArrived(slot);

        String warning;
        if (!result.coordinateSet()) {
            warning = "No meeting point is set for this experience, so the distance can't be measured.";
        } else if (!result.withinGeofence()) {
            warning = "Heads up: you're about " + Math.round(result.distanceMeters())
                    + " m from the meeting point.";
        } else {
            warning = null;
        }

        return new CheckInResponse(
                CheckInRole.HOST,
                checkIn.getCheckedInAt(),
                result.withinGeofence(),
                result.distanceMeters(),
                props.geofenceRadiusMeters(),
                warning,
                checkIn.getPhotoUrl());
    }

    // ---------------------------------------------------------------------------------------------
    // Host attendance roster + show / no-show
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SlotAttendanceResponse getSlotAttendance(UUID userId, UUID slotId) {
        AvailabilitySlot slot = requireSlotOwnedByHost(userId, slotId);
        Experience experience = slot.getExperience();

        Optional<AttendanceCheckIn> hostCheckIn =
                checkInRepository.findByAvailabilitySlotIdAndRole(slotId, CheckInRole.HOST);

        List<Booking> bookings = bookingRepository.findByAvailabilitySlotIdAndStatusIn(
                slotId, List.of(BookingStatus.CONFIRMED));

        Map<UUID, AttendanceCheckIn> guestCheckIns = checkInRepository
                .findByAvailabilitySlotIdAndRoleAndBookingIdIsNotNull(slotId, CheckInRole.GUEST)
                .stream()
                .collect(Collectors.toMap(c -> c.getBooking().getId(), Function.identity(), (a, b) -> a));

        List<BookingAttendanceRow> rows = bookings.stream()
                .map(b -> toRow(b, guestCheckIns.get(b.getId())))
                .toList();

        boolean meetingPointSet = experience.getLatitude() != null && experience.getLongitude() != null;

        return new SlotAttendanceResponse(
                slot.getId(),
                experience.getTitle(),
                slot.getStartTime(),
                meetingPointSet,
                props.geofenceRadiusMeters(),
                hostCheckIn.isPresent(),
                hostCheckIn.map(AttendanceCheckIn::getCheckedInAt).orElse(null),
                hostCheckIn.map(AttendanceCheckIn::isWithinGeofence).orElse(null),
                hostCheckIn.map(AttendanceCheckIn::getDistanceMeters).orElse(null),
                hostCheckIn.map(AttendanceCheckIn::getPhotoUrl).orElse(null),
                rows);
    }

    /** Host marks one booking's guest(s) as shown or not. */
    @Transactional
    public BookingAttendanceRow markGuestAttendance(UUID userId, UUID bookingId, MarkAttendanceRequest request) {
        if (request.status() == GuestShowStatus.PENDING) {
            throw new BadRequestException("Status must be SHOWED or NO_SHOW.");
        }
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        requireHostOwnsBooking(userId, booking);
        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("Attendance can only be marked for confirmed bookings.");
        }

        booking.setGuestShowStatus(request.status());
        booking.setGuestShowMarkedAt(Instant.now());
        bookingRepository.save(booking);

        AttendanceCheckIn guestCheckIn = checkInRepository
                .findByBookingIdAndRole(bookingId, CheckInRole.GUEST).orElse(null);
        return toRow(booking, guestCheckIn);
    }

    // ---------------------------------------------------------------------------------------------
    // Retention cleanup
    // ---------------------------------------------------------------------------------------------

    /** Operational data, not refund proof — purge check-ins past the retention window. */
    @Scheduled(fixedDelayString = "${app.checkin.retention-processor-delay-ms:86400000}")
    @Transactional
    public void purgeExpiredCheckIns() {
        Instant cutoff = Instant.now().minus(props.retentionDays(), ChronoUnit.DAYS);
        int deleted = checkInRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} attendance check-in(s) older than {} days", deleted, props.retentionDays());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void enforceWindow(AvailabilitySlot slot) {
        Instant start = slot.getStartTime();
        Instant now = Instant.now();
        Instant opensAt = start.minus(props.beforeMinutes(), ChronoUnit.MINUTES);
        Instant closesAt = start.plus(props.afterMinutes(), ChronoUnit.MINUTES);
        if (now.isBefore(opensAt)) {
            throw new BadRequestException(
                    "Check-in opens " + props.beforeMinutes() + " minutes before the start time.");
        }
        if (now.isAfter(closesAt)) {
            throw new BadRequestException(
                    "Check-in closed " + props.afterMinutes() + " minutes after the start time.");
        }
    }

    private GeoResult evaluateGeofence(Experience experience, BigDecimal lat, BigDecimal lng, Double accuracyMeters) {
        if (experience.getLatitude() == null || experience.getLongitude() == null) {
            return new GeoResult(null, false, false);
        }
        double distance = GeoUtil.distanceMeters(
                lat.doubleValue(), lng.doubleValue(),
                experience.getLatitude().doubleValue(), experience.getLongitude().doubleValue());
        double slack = Math.min(accuracyMeters == null ? 0.0 : accuracyMeters, props.maxAccuracyMeters());
        boolean within = (distance - slack) <= props.geofenceRadiusMeters();
        return new GeoResult(distance, within, true);
    }

    private void applyGeo(AttendanceCheckIn checkIn, CheckInRequest geo, GeoResult result) {
        checkIn.setLatitude(geo.latitude());
        checkIn.setLongitude(geo.longitude());
        checkIn.setAccuracyMeters(geo.accuracyMeters());
        checkIn.setDistanceMeters(result.distanceMeters());
        checkIn.setWithinGeofence(result.withinGeofence());
    }

    /**
     * Persists a check-in while tolerating a concurrent double-submit (e.g. an impatient double-tap).
     *
     * <p>An already-persisted row is just updated — no INSERT, so it can't trip the V23 partial unique
     * indexes. A brand-new row is inserted in its own transaction ({@link AttendanceCheckInWriter}); if a
     * parallel request inserted the same booking/slot row first, the unique index rejects ours with a
     * {@link DataIntegrityViolationException}. Because that insert ran in a separate transaction, ours
     * stays healthy, so we recover idempotently by re-reading the winning row.
     */
    private AttendanceCheckIn saveHandlingConcurrentInsert(
            AttendanceCheckIn checkIn, Supplier<Optional<AttendanceCheckIn>> reread) {
        if (checkIn.getId() != null) {
            return checkInRepository.save(checkIn);
        }
        try {
            return checkInWriter.insertNew(checkIn);
        } catch (DataIntegrityViolationException race) {
            return reread.get().orElseThrow(() -> race);
        }
    }

    private void notifyGuestsHostArrived(AvailabilitySlot slot) {
        List<Booking> bookings = bookingRepository.findByAvailabilitySlotIdAndStatusIn(
                slot.getId(), List.of(BookingStatus.CONFIRMED));
        String title = slot.getExperience() != null ? slot.getExperience().getTitle() : "your experience";
        String subject = "Your host has arrived";
        String message = "Your host has arrived at the meeting point for " + title
                + ". Head over when you're ready.";

        for (Booking booking : bookings) {
            String dedupeKey = "HOST_ARRIVED:" + booking.getId();
            if (booking.getLoggedInUser() != null) {
                notificationService.createEmailAndInAppNotificationForUser(
                        booking.getLoggedInUser(), NotificationType.HOST_ARRIVED,
                        subject, message, "BOOKING", booking.getId(), dedupeKey);
            } else {
                notificationService.createEmailNotificationForGuest(
                        booking.getGuestEmail(), booking.getGuestPhone(), NotificationType.HOST_ARRIVED,
                        subject, message, "BOOKING", booking.getId(), dedupeKey);
            }
        }
    }

    private BookingAttendanceRow toRow(Booking booking, AttendanceCheckIn guestCheckIn) {
        boolean loggedIn = booking.getLoggedInUser() != null;
        String displayName = loggedIn ? booking.getLoggedInUser().getFullName() : booking.getGuestName();
        String phone = loggedIn ? booking.getLoggedInUser().getPhone() : booking.getGuestPhone();
        return new BookingAttendanceRow(
                booking.getId(),
                booking.getBookingReference(),
                displayName,
                phone,
                booking.getGuestsCount() != null ? booking.getGuestsCount() : 0,
                booking.getGuestShowStatus(),
                guestCheckIn != null,
                guestCheckIn != null ? guestCheckIn.getCheckedInAt() : null,
                guestCheckIn != null ? guestCheckIn.getDistanceMeters() : null,
                guestCheckIn != null && guestCheckIn.isWithinGeofence());
    }

    private AvailabilitySlot requireSlotOwnedByHost(UUID userId, UUID slotId) {
        LocalProfile host = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        AvailabilitySlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
        Experience experience = slot.getExperience();
        if (experience == null || experience.getLocalProfile() == null
                || !experience.getLocalProfile().getId().equals(host.getId())) {
            throw new ResourceNotFoundException("Availability slot not found");
        }
        return slot;
    }

    private void requireHostOwnsBooking(UUID userId, Booking booking) {
        LocalProfile host = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        if (booking.getLocalProfile() == null
                || !booking.getLocalProfile().getId().equals(host.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }
    }

    /** Distance to the meeting point and whether the reading lands inside the (error-adjusted) geofence. */
    private record GeoResult(Double distanceMeters, boolean withinGeofence, boolean coordinateSet) {
    }
}
