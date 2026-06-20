package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TripSafetyService {

    private final EmergencyContactRepository emergencyContactRepository;
    private final TripSafetyEventRepository eventRepository;
    private final TripSafetyBookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final String supportEmail;

    public TripSafetyService(EmergencyContactRepository emergencyContactRepository,
                             TripSafetyEventRepository eventRepository,
                             TripSafetyBookingRepository bookingRepository,
                             UserRepository userRepository,
                             NotificationService notificationService,
                             @Value("${app.support.email:admin@test.com}") String supportEmail) {
        this.emergencyContactRepository = emergencyContactRepository;
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.supportEmail = supportEmail;
    }

    // --- Emergency contact -------------------------------------------------

    @Transactional(readOnly = true)
    public EmergencyContactResponse getMyEmergencyContact(UUID userId) {
        return emergencyContactRepository.findByUserId(userId)
                .map(EmergencyContactResponse::from)
                .orElse(null);
    }

    @Transactional
    public EmergencyContactResponse upsertEmergencyContact(UUID userId, UpsertEmergencyContactRequest request) {
        EmergencyContact contact = emergencyContactRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                    EmergencyContact created = new EmergencyContact();
                    created.setUser(user);
                    return created;
                });
        contact.setContactName(request.contactName().trim());
        contact.setContactPhone(request.contactPhone().trim());
        contact.setRelationship(trimToNull(request.relationship()));
        return EmergencyContactResponse.from(emergencyContactRepository.save(contact));
    }

    // --- Trip events -------------------------------------------------------

    @Transactional
    public TripSafetyEventResponse checkIn(UUID userId, UUID bookingId, TripCheckRequest request) {
        Booking booking = requireTraveler(userId, bookingId);
        return TripSafetyEventResponse.from(recordEvent(booking, userId,
                TripSafetyEventType.CHECK_IN,
                request != null ? request.latitude() : null,
                request != null ? request.longitude() : null,
                request != null ? request.note() : null));
    }

    @Transactional
    public TripSafetyEventResponse checkOut(UUID userId, UUID bookingId, TripCheckRequest request) {
        Booking booking = requireTraveler(userId, bookingId);
        return TripSafetyEventResponse.from(recordEvent(booking, userId,
                TripSafetyEventType.CHECK_OUT,
                request != null ? request.latitude() : null,
                request != null ? request.longitude() : null,
                request != null ? request.note() : null));
    }

    @Transactional
    public TripSafetyEventResponse raiseSos(UUID userId, UUID bookingId, SosRequest request) {
        Booking booking = requireTraveler(userId, bookingId);
        TripSafetyEvent event = recordEvent(booking, userId, TripSafetyEventType.SOS,
                request != null ? request.latitude() : null,
                request != null ? request.longitude() : null,
                request != null ? request.message() : null);

        notifySupport(booking, event);
        return TripSafetyEventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<TripSafetyEventResponse> getBookingEvents(UUID userId, UUID bookingId) {
        requireParticipant(userId, bookingId);
        return eventRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)
                .stream().map(TripSafetyEventResponse::from).toList();
    }

    // --- Admin -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TripSafetyEventResponse> listOpenSos() {
        return eventRepository.findByEventTypeAndResolvedFalseOrderByCreatedAtAsc(TripSafetyEventType.SOS)
                .stream().map(TripSafetyEventResponse::from).toList();
    }

    @Transactional
    public TripSafetyEventResponse resolveSos(UUID eventId) {
        TripSafetyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Safety event not found"));
        if (event.getEventType() != TripSafetyEventType.SOS) {
            throw new BadRequestException("Only SOS events can be resolved");
        }
        event.setResolved(true);
        event.setResolvedAt(Instant.now());
        return TripSafetyEventResponse.from(eventRepository.save(event));
    }

    // --- Helpers -----------------------------------------------------------

    private TripSafetyEvent recordEvent(Booking booking, UUID userId, TripSafetyEventType type,
                                        Double latitude, Double longitude, String note) {
        User user = userRepository.findById(userId).orElse(null);
        TripSafetyEvent event = new TripSafetyEvent();
        event.setBooking(booking);
        event.setUser(user);
        event.setEventType(type);
        event.setLatitude(latitude);
        event.setLongitude(longitude);
        event.setNote(trimToNull(note));
        return eventRepository.save(event);
    }

    private void notifySupport(Booking booking, TripSafetyEvent event) {
        String reference = booking.getBookingReference();
        StringBuilder message = new StringBuilder();
        message.append("SOS raised for booking ").append(reference).append(".");
        if (event.getLatitude() != null && event.getLongitude() != null) {
            message.append(" Location: ").append(event.getLatitude()).append(", ").append(event.getLongitude()).append(".");
        }
        if (event.getNote() != null) {
            message.append(" Message: ").append(event.getNote()).append(".");
        }

        User traveler = booking.getTravelerUser();
        if (traveler != null) {
            emergencyContactRepository.findByUserId(traveler.getId()).ifPresent(contact ->
                    message.append(" Emergency contact: ").append(contact.getContactName())
                            .append(" (").append(contact.getContactPhone()).append(")."));
        }

        notificationService.createEmailNotificationForGuest(
                supportEmail, null, NotificationType.SAFETY_REPORT_CREATED,
                "SOS: booking " + reference, message.toString(),
                "BOOKING", booking.getId(), "sos:" + event.getId());
    }

    private Booking requireTraveler(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        User traveler = booking.getTravelerUser();
        if (traveler == null || !traveler.getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    private Booking requireParticipant(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        boolean isTraveler = booking.getTravelerUser() != null
                && booking.getTravelerUser().getId().equals(userId);
        boolean isHost = booking.getLocalProfile() != null
                && booking.getLocalProfile().getUser() != null
                && booking.getLocalProfile().getUser().getId().equals(userId);
        if (!isTraveler && !isHost) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
