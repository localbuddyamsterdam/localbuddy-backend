package com.localbuddy.tripsafety;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingSource;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TripSafetyService {

    private static final Logger log = LoggerFactory.getLogger(TripSafetyService.class);

    private final EmergencyContactRepository emergencyContactRepository;
    private final TripSafetyEventRepository eventRepository;
    private final TripSafetyBookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SosAlertBuilder sosAlertBuilder;
    private final GeocodingService geocodingService;
    private final String supportEmail;
    /** Comma-separated operator emails that receive live SOS alerts; blank falls back to support email. */
    private final String sosRecipientsRaw;
    /** When true, also alert the traveler's emergency contact on an SOS. */
    private final boolean notifyEmergencyContactEnabled;

    public TripSafetyService(EmergencyContactRepository emergencyContactRepository,
                             TripSafetyEventRepository eventRepository,
                             TripSafetyBookingRepository bookingRepository,
                             UserRepository userRepository,
                             NotificationService notificationService,
                             SosAlertBuilder sosAlertBuilder,
                             GeocodingService geocodingService,
                             @Value("${app.support.email:admin@test.com}") String supportEmail,
                             @Value("${app.trip-safety.sos-recipients:}") String sosRecipientsRaw,
                             @Value("${app.trip-safety.notify-emergency-contact:true}") boolean notifyEmergencyContactEnabled) {
        this.emergencyContactRepository = emergencyContactRepository;
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.sosAlertBuilder = sosAlertBuilder;
        this.geocodingService = geocodingService;
        this.supportEmail = supportEmail;
        this.sosRecipientsRaw = sosRecipientsRaw;
        this.notifyEmergencyContactEnabled = notifyEmergencyContactEnabled;
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
        contact.setFirstName(request.firstName().trim());
        contact.setLastName(request.lastName().trim());
        contact.setEmail(trimToNull(request.email()));
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
        User user = userRepository.findById(userId).orElse(null);
        TripSafetyEvent event = eventRepository.save(buildSosEvent(booking, user, request));
        geocode(event);
        notifySupport(booking, event, false);
        notifyEmergencyContact(booking, event);
        return TripSafetyEventResponse.from(event);
    }

    /**
     * Anonymous-guest SOS: resolve the guest booking by reference + email, then alert
     * exactly like an authenticated SOS. Safety is never login-gated. Every mismatch
     * throws the same opaque 404 so the endpoint can't be used to probe bookings.
     */
    @Transactional
    public TripSafetyEventResponse raiseGuestSos(PublicSosRequest request) {
        String reference = request.bookingReference() == null ? ""
                : request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String email = request.guestEmail() == null ? ""
                : request.guestEmail().trim().toLowerCase(Locale.ROOT);
        Booking booking = bookingRepository.findByBookingReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));
        if (booking.getBookingSource() != BookingSource.GUEST_USER
                || booking.getGuestEmail() == null
                || !booking.getGuestEmail().equalsIgnoreCase(email)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }
        TripSafetyEvent event = eventRepository.save(buildSosEvent(booking, null, request.toSosRequest()));
        geocode(event);
        notifySupport(booking, event, false);
        notifyEmergencyContact(booking, event);
        return TripSafetyEventResponse.from(event);
    }

    /**
     * Enrich an already-raised SOS from the one-tap chips on the reassurance screen
     * (situation type, contactability, an added note). Only ever updates an existing
     * open SOS the traveler owns; re-alerts operators so the new detail reaches them.
     */
    @Transactional
    public TripSafetyEventResponse updateSosDetail(UUID userId, UUID bookingId, UUID eventId, SosDetailRequest request) {
        Booking booking = requireTraveler(userId, bookingId);
        TripSafetyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Safety event not found"));
        if (event.getBooking() == null || !event.getBooking().getId().equals(bookingId)
                || event.getEventType() != TripSafetyEventType.SOS) {
            throw new BadRequestException("Not an SOS event for this booking");
        }
        boolean changed = false;
        if (request != null && request.situationType() != null) {
            event.setSituationType(request.situationType());
            changed = true;
        }
        if (request != null && request.contactPreference() != null) {
            event.setContactPreference(request.contactPreference());
            changed = true;
        }
        if (request != null && trimToNull(request.message()) != null) {
            event.setNote(trimToNull(request.message()));
            changed = true;
        }
        event = eventRepository.save(event);
        if (changed && !event.isResolved()) {
            notifySupport(booking, event, true);
        }
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

    /** A responder has eyes on this SOS (first acknowledgement wins). */
    @Transactional
    public TripSafetyEventResponse acknowledgeSos(UUID eventId, UUID adminId) {
        TripSafetyEvent event = requireSosEvent(eventId);
        if (event.getAcknowledgedAt() == null) {
            event.setAcknowledgedAt(Instant.now());
            event.setAcknowledgedBy(adminId);
        }
        return TripSafetyEventResponse.from(eventRepository.save(event));
    }

    /** Bump this SOS to on-call and re-fire the operator alert. */
    @Transactional
    public TripSafetyEventResponse escalateSos(UUID eventId) {
        TripSafetyEvent event = requireSosEvent(eventId);
        event.setEscalatedAt(Instant.now());
        event = eventRepository.save(event);
        notifySupport(event.getBooking(), event, true);
        return TripSafetyEventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public long openSosCount() {
        return eventRepository.countByEventTypeAndResolvedFalse(TripSafetyEventType.SOS);
    }

    private TripSafetyEvent requireSosEvent(UUID eventId) {
        TripSafetyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Safety event not found"));
        if (event.getEventType() != TripSafetyEventType.SOS) {
            throw new BadRequestException("Not an SOS event");
        }
        return event;
    }

    // --- Helpers -----------------------------------------------------------

    private TripSafetyEvent buildSosEvent(Booking booking, User user, SosRequest request) {
        TripSafetyEvent event = new TripSafetyEvent();
        event.setBooking(booking);
        event.setUser(user);
        event.setEventType(TripSafetyEventType.SOS);
        // A missing situation type is treated as a full emergency (fail-safe); a missing
        // contact preference defaults to "OK to call".
        event.setSituationType(request != null && request.situationType() != null
                ? request.situationType() : SosSituationType.EMERGENCY);
        event.setContactPreference(request != null && request.contactPreference() != null
                ? request.contactPreference() : SosContactPreference.CALL);
        if (request != null) {
            event.setLatitude(request.latitude());
            event.setLongitude(request.longitude());
            event.setNote(trimToNull(request.message()));
            event.setAccuracyMeters(request.accuracyMeters());
            event.setLocationSource(trimToNull(request.locationSource()));
            event.setBatteryPercent(request.batteryPercent());
            event.setDeviceLanguage(trimToNull(request.deviceLanguage()));
        }
        return event;
    }

    /** Best-effort reverse geocode; persists the address on the event. No-op unless a
     * geocoding provider is configured, and never throws (an SOS must not depend on it). */
    private void geocode(TripSafetyEvent event) {
        try {
            geocodingService.reverseGeocode(event.getLatitude(), event.getLongitude())
                    .ifPresent(address -> {
                        event.setGeocodedAddress(address);
                        eventRepository.save(event);
                    });
        } catch (Exception e) {
            log.warn("SOS reverse-geocode failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Alert the traveler's emergency contact (per-booking snapshot, else profile EC) via
     * email and — when a number is on file — WhatsApp (dormant unless configured). */
    private void notifyEmergencyContact(Booking booking, TripSafetyEvent event) {
        if (!notifyEmergencyContactEnabled) {
            return;
        }
        String ecEmail = trimToNull(booking.getEmergencyContactEmail());
        String ecPhone = trimToNull(booking.getEmergencyContactPhone());
        if (ecEmail == null && ecPhone == null && booking.getLoggedInUser() != null) {
            EmergencyContact profileEc = emergencyContactRepository
                    .findByUserId(booking.getLoggedInUser().getId()).orElse(null);
            if (profileEc != null) {
                ecEmail = trimToNull(profileEc.getEmail());
                ecPhone = trimToNull(profileEc.getContactPhone());
            }
        }
        if (ecEmail == null && ecPhone == null) {
            return;
        }
        String travelerName = booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getDisplayName() : booking.getGuestName();
        if (travelerName == null || travelerName.isBlank()) {
            travelerName = "Someone you are an emergency contact for";
        }
        String reference = booking.getBookingReference();
        StringBuilder body = new StringBuilder();
        body.append(travelerName).append(" has raised an SOS during their LocalBuddy experience (booking ")
                .append(reference).append(").");
        if (event.getGeocodedAddress() != null) {
            body.append(" Last known location: ").append(event.getGeocodedAddress()).append('.');
        } else if (event.getLatitude() != null && event.getLongitude() != null) {
            body.append(" Location: https://www.google.com/maps/search/?api=1&query=")
                    .append(event.getLatitude()).append(',').append(event.getLongitude()).append('.');
        }
        body.append(" Please try to reach them. If you can't, contact local emergency services.");
        String subject = "Safety alert: " + travelerName + " raised an SOS";
        String message = body.toString();

        if (ecEmail != null) {
            notificationService.createEmailNotificationForGuest(
                    ecEmail, ecPhone, NotificationType.SOS_RAISED, subject, message,
                    "BOOKING", booking.getId(), "sos-ec:" + event.getId());
        }
        if (ecPhone != null) {
            // No-ops unless WhatsApp Business is configured; harmless otherwise.
            notificationService.createWhatsAppNotificationForGuest(
                    ecEmail, ecPhone, NotificationType.SOS_RAISED, subject, message,
                    "BOOKING", booking.getId(), "sos-ec-wa:" + event.getId());
        }
    }

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

    private void notifySupport(Booking booking, TripSafetyEvent event, boolean update) {
        User traveler = booking.getLoggedInUser();
        EmergencyContact profileEc = traveler != null
                ? emergencyContactRepository.findByUserId(traveler.getId()).orElse(null)
                : null;

        SosAlertBuilder.SosAlertContent alert = sosAlertBuilder.build(booking, event, profileEc, update);

        for (String recipient : resolveSosRecipients()) {
            // Distinct dedupe key per recipient + per (raise vs update) so operators are not
            // silently deduped out of a follow-up.
            String dedupeKey = "sos:" + event.getId() + (update ? ":upd" : "") + ":" + recipient;
            notificationService.createEmailNotificationForGuest(
                    recipient, null, NotificationType.SOS_RAISED,
                    alert.subject(), alert.textBody(), alert.htmlBody(),
                    "BOOKING", booking.getId(), dedupeKey);
        }

        // Surface it inside the admin console on the initial raise (not on every traveler
        // chip-tap update); escalation re-fires the operator email above.
        if (!update) {
            notifyAdminsInApp(booking, event);
        }
    }

    /** Also surface a raised SOS inside the admin console: an in-app notification to every
     * ADMIN and SUPER_ADMIN account, so a logged-in admin sees it without an inbox. */
    private void notifyAdminsInApp(Booking booking, TripSafetyEvent event) {
        List<User> admins = new ArrayList<>();
        admins.addAll(userRepository.findByRole(UserRole.ADMIN));
        admins.addAll(userRepository.findByRole(UserRole.SUPER_ADMIN));
        if (admins.isEmpty()) {
            return;
        }
        String situation = event.getSituationType() != null ? event.getSituationType().name() : "EMERGENCY";
        String location = event.getGeocodedAddress() != null
                ? event.getGeocodedAddress()
                : (event.getLatitude() != null && event.getLongitude() != null
                        ? event.getLatitude() + ", " + event.getLongitude()
                        : "location unavailable");
        String subject = "SOS raised · booking " + booking.getBookingReference();
        StringBuilder message = new StringBuilder(situation).append(" — ").append(location);
        if (event.getContactPreference() != null && event.getContactPreference() != SosContactPreference.CALL) {
            message.append(" · ").append(event.getContactPreference().name());
        }
        if (event.getNote() != null) {
            message.append(" · \"").append(event.getNote()).append('"');
        }
        for (User admin : admins) {
            notificationService.createInAppNotificationForUser(
                    admin, NotificationType.SOS_RAISED, subject, message.toString(),
                    "BOOKING", booking.getId(), "sos-admin:" + event.getId() + ":" + admin.getId());
        }
    }

    /** Operator emails for SOS alerts; falls back to the support email, and WARNs loudly
     * when that fallback is still the unset/dev default — an SOS reaching no one is dangerous. */
    private List<String> resolveSosRecipients() {
        List<String> recipients = new ArrayList<>();
        if (sosRecipientsRaw != null && !sosRecipientsRaw.isBlank()) {
            for (String part : sosRecipientsRaw.split(",")) {
                String email = part.trim();
                if (!email.isEmpty()) {
                    recipients.add(email);
                }
            }
        }
        if (recipients.isEmpty()) {
            recipients.add(supportEmail);
            if (supportEmail == null || supportEmail.isBlank() || supportEmail.equalsIgnoreCase("admin@test.com")) {
                log.warn("SOS alert is routing to the default/unset support email ({}). Set "
                        + "app.trip-safety.sos-recipients (SOS_ALERT_EMAILS) to a MONITORED inbox — "
                        + "an SOS reaching admin@test.com reaches no one.", supportEmail);
            }
        }
        return recipients;
    }

    private Booking requireTraveler(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        User traveler = booking.getLoggedInUser();
        if (traveler == null || !traveler.getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    private Booking requireParticipant(UUID userId, UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        boolean isTraveler = booking.getLoggedInUser() != null
                && booking.getLoggedInUser().getId().equals(userId);
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
