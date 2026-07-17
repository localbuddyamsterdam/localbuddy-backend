package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.availability.BookingWindowPolicy;
import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.ConsentService;
import com.localbuddy.experience.BookingMode;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.messaging.ConversationRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.whatsapp.WhatsAppTemplates;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.promo.AppliedPromoCode;
import com.localbuddy.promo.AppliedPromoCodes;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.referral.AppliedReferralCode;
import com.localbuddy.referral.ReferralService;
import com.localbuddy.safety.BookingSafetyChecklistRepository;
import com.localbuddy.trustsafety.TrustSafetyService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.waitlist.WaitlistService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import com.localbuddy.deals.AppliedDeal;
import com.localbuddy.deals.DealService;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final String REFERENCE_PREFIX = "LB";
    /** A host may only cancel a booking more than this many hours before the experience starts. */
    private static final long HOST_CANCEL_MIN_HOURS = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ExperienceRepository experienceRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final LocalProfileRepository localProfileRepository;
    private final NotificationService notificationService;
    private final WhatsAppTemplates whatsAppTemplates;
    private final ConsentService consentService;
    private final PromoCodeService promoCodeService;
    private final ReferralService referralService;
    private final BookingSafetyChecklistRepository bookingSafetyChecklistRepository;
    private final PaymentService paymentService;
    private final TrustSafetyService trustSafetyService;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingReferenceGenerator bookingReferenceGenerator;
    private final WaitlistService waitlistService;
    private final AgeBandPricing ageBandPricing;
    private final BookingConfirmationNotifier bookingConfirmationNotifier;
    private final ConversationRepository conversationRepository;
    private final DealService dealService;
    private final BookingWindowPolicy bookingWindowPolicy;

    public BookingService(BookingRepository bookingRepository,
                          UserRepository userRepository,
                          ExperienceRepository experienceRepository,
                          AvailabilitySlotRepository availabilitySlotRepository,
                          LocalProfileRepository localProfileRepository, NotificationService notificationService, WhatsAppTemplates whatsAppTemplates, ConsentService consentService, PromoCodeService promoCodeService, ReferralService referralService, BookingSafetyChecklistRepository bookingSafetyChecklistRepository, PaymentService paymentService, TrustSafetyService trustSafetyService, ApplicationEventPublisher eventPublisher, BookingReferenceGenerator bookingReferenceGenerator, WaitlistService waitlistService, AgeBandPricing ageBandPricing, BookingConfirmationNotifier bookingConfirmationNotifier, ConversationRepository conversationRepository, DealService dealService, BookingWindowPolicy bookingWindowPolicy) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.experienceRepository = experienceRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.localProfileRepository = localProfileRepository;
        this.notificationService = notificationService;
        this.whatsAppTemplates = whatsAppTemplates;
        this.consentService = consentService;
        this.promoCodeService = promoCodeService;
        this.referralService = referralService;
        this.bookingSafetyChecklistRepository = bookingSafetyChecklistRepository;
        this.paymentService = paymentService;
        this.trustSafetyService = trustSafetyService;
        this.eventPublisher = eventPublisher;
        this.bookingReferenceGenerator = bookingReferenceGenerator;
        this.waitlistService = waitlistService;
        this.ageBandPricing = ageBandPricing;
        this.bookingConfirmationNotifier = bookingConfirmationNotifier;
        this.conversationRepository = conversationRepository;
        this.dealService = dealService;
        this.bookingWindowPolicy = bookingWindowPolicy;
    }

    @Transactional
    public BookingResponse createBooking(UUID loggedInUserId, CreateBookingRequest request) {
        long totalStart = System.currentTimeMillis();

        long stepStart = System.currentTimeMillis();
        User traveler = userRepository.findById(loggedInUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));
        log.info("LOGGED_IN_BOOKING_TIMING userLookupMs={}", System.currentTimeMillis() - stepStart);

        // Any signed-in account (traveler, host, admin) can book as a customer. The only
        // role-specific rule is that skipping payment is an admin-only privilege — enforced
        // here on the JWT-derived user, never trusted from the client.
        stepStart = System.currentTimeMillis();
        boolean skipPayment = Boolean.TRUE.equals(request.skipPayment());
        if (skipPayment && !traveler.isAdminTier()) {
            throw new BadRequestException("Only admins can book without payment");
        }
        log.info("LOGGED_IN_BOOKING_TIMING roleCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        trustSafetyService.requireUserCanBook(loggedInUserId);
        log.info("LOGGED_IN_BOOKING_TIMING travelerRestrictionCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        consentService.requireTravelerConsents(loggedInUserId);
        log.info("LOGGED_IN_BOOKING_TIMING travelerConsentCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (bookingRepository.existsByLoggedInUserIdAndAvailabilitySlotIdAndStatusIn(
                loggedInUserId,
                request.availabilitySlotId(),
                activeBookingStatuses()
        )) {
            throw new BadRequestException("You already have an active booking for this slot");
        }
        log.info("LOGGED_IN_BOOKING_TIMING duplicateCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        Experience experience = experienceRepository.findWithLocalProfileAndUserById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        log.info("LOGGED_IN_BOOKING_TIMING experienceLookupMs={}", System.currentTimeMillis() - stepStart);

        if (traveler.getRole() == UserRole.LOCAL
                && experience.getLocalProfile().getUser().getId().equals(loggedInUserId)) {
            throw new BadRequestException("You can't book your own experience");
        }

        stepStart = System.currentTimeMillis();
        trustSafetyService.requireUserCanHost(
                experience.getLocalProfile().getUser().getId()
        );
        log.info("LOGGED_IN_BOOKING_TIMING hostRestrictionCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience is not available for booking");
        }
        log.info("LOGGED_IN_BOOKING_TIMING experienceStatusCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(request.availabilitySlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
        log.info("LOGGED_IN_BOOKING_TIMING slotLockLookupMs={}", System.currentTimeMillis() - stepStart);

        AgeBandPricing.AgeBands bands = ageBandPricing.resolve(
                request.adults(), request.teens(), request.children(), request.infants(), request.guestsCount());
        ageBandPricing.validateAgeGate(experience.getMinimumAge(), bands);

        stepStart = System.currentTimeMillis();
        validateSlot(experience, slot, bands.seats());
        log.info("LOGGED_IN_BOOKING_TIMING validateSlotMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, experience, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot,
                pricePerGuest, experience.getPrivatePrice(),
                bands.seats(), ageBandPricing.billableUnits(bands));

        int seatsToBook = pricing.seatsBlocked();
        int newBookedCount = slot.getBookedCount() + seatsToBook;
        slot.setBookedCount(newBookedCount);

        if (newBookedCount >= slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.BLOCKED);
        }

        BigDecimal originalAmount = pricing.originalAmount();
        String currency = experience.getCurrency().toUpperCase(Locale.ROOT);
        log.info("LOGGED_IN_BOOKING_TIMING updateSlotMemoryMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedDeal appliedDeal = dealService.resolveDealForBooking(experience, pricing.baseForPromo());
        BigDecimal baseAfterDeal = pricing.baseForPromo().subtract(appliedDeal.discountAmount());
        AppliedPromoCodes appliedPromos = promoCodeService.applyPromoCodesForBooking(
                loggedInUserId,
                mergePromoCodes(request.promoCode(), request.promoCodes()),
                null,
                baseAfterDeal,
                currency,
                experience.getId()
        );
        log.info("LOGGED_IN_BOOKING_TIMING applyPromoMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedReferralCode appliedReferral = referralService.applyReferralCodeForBooking(
                loggedInUserId,
                request.referralCode(),
                null
        );
        log.info("LOGGED_IN_BOOKING_TIMING applyReferralMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        BigDecimal referralDiscount = referralDiscountFor(appliedReferral, appliedPromos.finalAmount());
        BigDecimal totalAmount = appliedPromos.finalAmount().subtract(referralDiscount);

        Booking booking = new Booking();
        booking.setBookingReference(generateUniqueBookingReference());
        booking.setLoggedInUser(traveler);
        booking.setBookingSource(BookingSource.LOGGED_IN_USER);
        booking.setLocalProfile(experience.getLocalProfile());
        booking.setExperience(experience);
        booking.setAvailabilitySlot(slot);
        booking.setGuestsCount(bands.totalGuests());
        applyBands(booking, bands);
        booking.setPrivateBooking(privateBooking);
        booking.setSeatsBlocked(seatsToBook);
        // Admin skip-payment bookings are confirmed on the spot with no Payment row —
        // the same shape as console-created bookings — and flagged so finance can
        // exclude them (no ledger entry, no invoice, nothing to refund).
        if (skipPayment) {
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setPaymentWaived(true);
            booking.setAcceptedAt(Instant.now());
        } else {
            booking.setStatus(BookingStatus.PENDING_PAYMENT);
        }

        booking.setPricePerGuest(pricePerGuest);
        booking.setOriginalAmount(originalAmount);
        booking.setPrivateDiscountAmount(pricing.privateDiscountAmount());
        booking.setDiscountAmount(appliedPromos.totalDiscount());
        booking.setDealId(appliedDeal.dealId());
        booking.setDealDiscountAmount(appliedDeal.discountAmount());
        booking.setTotalAmount(totalAmount);
        booking.setCurrency(currency);

        applyPromoCodesToBooking(booking, appliedPromos);

        booking.setReferralCode(appliedReferral.referralCode());
        booking.setReferralCodeText(optionalUpper(request.referralCode()));
        booking.setReferralDiscountAmount(referralDiscount);

        booking.setTravelerNote(optionalTrim(request.travelerNote()));
        applyEmergencyContact(booking,
                request.emergencyContactFirstName(),
                request.emergencyContactLastName(),
                request.emergencyContactEmail(),
                request.emergencyContactPhone(),
                request.emergencyContactRelationship());
        booking.setWhatsappOptIn(Boolean.TRUE.equals(request.whatsAppOptIn()));
        booking.setRequestedAt(Instant.now());
        log.info("LOGGED_IN_BOOKING_TIMING buildBookingObjectMs={}", System.currentTimeMillis() - stepStart);

        try {
            stepStart = System.currentTimeMillis();
            availabilitySlotRepository.save(slot);
            log.info("LOGGED_IN_BOOKING_TIMING saveSlotMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            Booking savedBooking = bookingRepository.save(booking);
            log.info("LOGGED_IN_BOOKING_TIMING saveBookingMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            if (skipPayment) {
                // Already confirmed — send the confirmation directly (like console bookings)
                // instead of the created/awaiting-payment notification.
                bookingConfirmationNotifier.sendConfirmation(savedBooking);
            } else {
                eventPublisher.publishEvent(new BookingCreatedEvent(savedBooking.getId()));
            }
            log.info("LOGGED_IN_BOOKING_TIMING publishEventMs={}", System.currentTimeMillis() - stepStart);
            publishBookingAudit(savedBooking.getId(), "BOOKED", "Booking created", loggedInUserId, "TRAVELER");

            stepStart = System.currentTimeMillis();
            BookingResponse response = toResponse(savedBooking);
            log.info("LOGGED_IN_BOOKING_TIMING toResponseMs={}", System.currentTimeMillis() - stepStart);

            log.info("LOGGED_IN_BOOKING_TIMING totalMs={}", System.currentTimeMillis() - totalStart);

            return response;

        } catch (DataIntegrityViolationException ex) {
            log.warn("LOGGED_IN_BOOKING_TIMING failedAfterMs={} reason=data_integrity_violation",
                    System.currentTimeMillis() - totalStart);

            throw new BadRequestException("You already have an active booking for this slot");
        }
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(UUID userId) {
        // "My trips" is the caller's own traveler bookings for every role. Hosts see
        // incoming bookings via their host surfaces and admins via /api/admin — this
        // endpoint must never widen beyond the personal scope (an admin's account page
        // used to leak every user's bookings through the old findAll fallback).
        return bookingRepository.findByLoggedInUserIdOrderByRequestedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Resolved pricing + seat usage for a booking, accounting for the private
     * (whole-slot buyout) option.
     */
    private record BookingPricing(
            BigDecimal originalAmount,
            BigDecimal privateDiscountAmount,
            BigDecimal baseForPromo,
            int seatsBlocked
    ) {
    }

    /**
     * Computes the pricing for a booking.
     *
     * <p>Private booking (PRIVATE_ONLY or PRIVATE_ALLOWED): the host's flat {@code privatePrice}
     * is charged as-is — no per-person calculation, no discount. Promos apply on the flat price.
     * The whole slot is blocked (seats = capacity).
     * <p>Shared booking: {@code guestsCount × pricePerGuest} (age-band weighted).
     */
    private BookingPricing computePricing(boolean privateBooking,
                                          AvailabilitySlot slot,
                                          BigDecimal pricePerGuest,
                                          BigDecimal flatPrivatePrice,
                                          int seats,
                                          BigDecimal billableUnits) {
        if (privateBooking) {
            // Whole-slot buyout — host's flat private total, no discount, blocks all seats.
            return new BookingPricing(flatPrivatePrice,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    flatPrivatePrice,
                    slot.getCapacity());
        }

        // Shared booking: price by weighted billable units (age bands), seats by head count.
        BigDecimal originalAmount = pricePerGuest
                .multiply(billableUnits)
                .setScale(2, RoundingMode.HALF_UP);
        return new BookingPricing(
                originalAmount,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                originalAmount,
                seats
        );
    }

    private void applyBands(Booking booking, AgeBandPricing.AgeBands bands) {
        booking.setAdultsCount(bands.adults());
        booking.setTeensCount(bands.teens());
        booking.setChildrenCount(bands.children());
        booking.setInfantsCount(bands.infants());
    }

    /** Number of slot seats a booking consumes (whole capacity for a private buyout). */
    private int seatsConsumed(Booking booking) {
        return booking.getSeatsBlocked() != null ? booking.getSeatsBlocked() : booking.getGuestsCount();
    }

    private void requirePrivateBookingAllowed(boolean privateBooking, Experience experience, AvailabilitySlot slot) {
        BookingMode mode = experience.getBookingMode();
        if (privateBooking && experience.isListedOnExternalPlatform()) {
            throw new BadRequestException(
                    "Private booking is not available for experiences listed on external booking platforms");
        }
        if (privateBooking && mode == BookingMode.SHARED) {
            throw new BadRequestException("This experience does not offer private bookings");
        }
        if (!privateBooking && mode == BookingMode.PRIVATE_ONLY) {
            throw new BadRequestException("This experience only accepts private (whole-slot) bookings");
        }
        if (privateBooking && slot.getBookedCount() != 0) {
            throw new BadRequestException(
                    "Private booking is not available because seats are already booked for this slot");
        }
    }

    private void validateSlot(Experience experience, AvailabilitySlot slot, int guestsCount) {
        if (!slot.getExperience().getId().equals(experience.getId())) {
            throw new BadRequestException("Availability slot does not belong to experience");
        }

        if (slot.getStatus() != AvailabilityStatus.AVAILABLE) {
            throw new BadRequestException("Availability slot is not available");
        }

        Instant now = Instant.now();
        if (!slot.getStartTime().isAfter(now)) {
            throw new BadRequestException("Cannot book a past slot");
        }

        if (!bookingWindowPolicy.isBookableAt(slot, now)) {
            throw new BadRequestException(
                    "Booking for this session has closed (bookings close "
                    + bookingWindowPolicy.leadMinutes(slot) + " minutes before it starts)");
        }

        int remainingCapacity = slot.getCapacity() - slot.getBookedCount();

        if (guestsCount > remainingCapacity) {
            throw new BadRequestException("Not enough remaining capacity");
        }
    }

    private String generateUniqueBookingReference() {
        return REFERENCE_PREFIX + "-" + randomAlphaNumeric(12);
    }

    private String randomAlphaNumeric(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder builder = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }

        return builder.toString();
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private BookingResponse toResponse(Booking booking) {
        return toResponse(booking, true);
    }

    /**
     * @param includeEmergencyContact false for host-facing (LOCAL) responses. The
     *   emergency contact is the traveller's third party; the host UI never renders it and
     *   an SOS escalates to support (not the host), so it's withheld from hosts for data
     *   minimization. Travellers (their own booking), admins and support still receive it.
     */
    private BookingResponse toResponse(Booking booking, boolean includeEmergencyContact) {
        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getLoggedInUser() != null ? booking.getLoggedInUser().getId() : null,
                booking.getGuestFirstName(),
                booking.getGuestLastName(),
                booking.getGuestEmail(),
                booking.getGuestPhone(),
                booking.isGuestEmailVerified(),
                booking.isGuestPhoneVerified(),
                booking.getBookingSource(),
                booking.getLocalProfile().getId(),
                booking.getExperience().getId(),
                booking.getAvailabilitySlot().getId(),
                booking.getGuestsCount(),
                booking.getStatus(),
                booking.getPricePerGuest(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                booking.getTravelerNote(),
                booking.getLocalResponseNote(),
                booking.getCancellationReason(),
                booking.getRequestedAt(),
                booking.getAcceptedAt(),
                booking.getDeclinedAt(),
                booking.getCancelledAt(),
                booking.getCompletedAt(),
                booking.getCreatedAt(),
                booking.getUpdatedAt(),
                booking.isGuestTermsAccepted(),
                booking.isGuestSafetyAccepted(),
                booking.isGuestLiabilityAccepted(),
                booking.getGuestConsentVersion(),
                booking.getGuestConsentAcceptedAt(),
                booking.getPromoCode() != null ? booking.getPromoCode().getId() : null,
                booking.getReferralCode() != null ? booking.getReferralCode().getId() : null,
                booking.getOriginalAmount(),
                booking.getDiscountAmount(),
                booking.getPromoCodeText(),
                booking.getReferralCodeText(),
                booking.isPrivateBooking(),
                booking.getPrivateDiscountAmount(),
                booking.getSeatsBlocked(),
                booking.getDealId(),
                booking.getDealDiscountAmount(),
                booking.getAvailabilitySlot().getStartTime(),
                booking.getAvailabilitySlot().getEndTime(),
                booking.getExperience().getTitle(),
                booking.getLocalProfile().getDisplayName(),
                booking.getExperience().getCity() != null ? booking.getExperience().getCity().getName() : null,
                booking.getAttendanceOutcome(),
                booking.getNoShowMarkedAt(),
                booking.getGuestShowStatus(),
                booking.getAdultsCount(),
                booking.getTeensCount(),
                booking.getChildrenCount(),
                booking.getInfantsCount(),
                includeEmergencyContact ? booking.getEmergencyContactFirstName() : null,
                includeEmergencyContact ? booking.getEmergencyContactLastName() : null,
                includeEmergencyContact ? booking.getEmergencyContactEmail() : null,
                includeEmergencyContact ? booking.getEmergencyContactPhone() : null,
                includeEmergencyContact ? booking.getEmergencyContactRelationship() : null,
                booking.isPaymentWaived(),
                booking.getReferralDiscountAmount(),
                booking.getExperience().getCity() != null
                        ? booking.getExperience().getCity().getTimezone()
                        : null
        );
    }

    /**
     * Apply an optional emergency-contact snapshot to a new booking.
     * <p>
     * "All-or-nothing": if every field is blank the contact is skipped (left null).
     * As soon as any core field is provided, first name, last name, phone and
     * relationship are all required (email stays optional) — this mirrors the
     * grouped-required rule the checkout form enforces, so a half-entered contact
     * can never be persisted.
     */
    private void applyEmergencyContact(Booking booking,
                                       String firstName,
                                       String lastName,
                                       String email,
                                       String phone,
                                       String relationship) {
        String first = optionalTrim(firstName);
        String last = optionalTrim(lastName);
        String mail = optionalTrim(email);
        String tel = optionalTrim(phone);
        String rel = optionalTrim(relationship);

        boolean anyProvided = first != null || last != null || tel != null || rel != null || mail != null;
        if (!anyProvided) {
            return;
        }
        // Data-integrity floor: a first name + phone are the minimum to be reachable.
        // The checkout form enforces the richer first/last/phone/relationship rule for a
        // newly-entered contact; this floor also accepts an unedited prefill of a saved
        // profile contact (which may legitimately lack a relationship or last name).
        if (first == null || tel == null) {
            throw new BadRequestException(
                    "An emergency contact needs at least a first name and a phone number.");
        }

        booking.setEmergencyContactFirstName(first);
        booking.setEmergencyContactLastName(last);
        booking.setEmergencyContactEmail(mail);
        booking.setEmergencyContactPhone(tel);
        booking.setEmergencyContactRelationship(rel);
    }

    @Transactional
    public BookingResponse acceptBooking(UUID localUserId, UUID bookingId, BookingDecisionRequest request) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }

        consentService.requireLocalConsents(localUserId);

        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            throw new BadRequestException("This booking is already ready for payment and does not require local acceptance");
        }

        if (booking.getStatus() != BookingStatus.REQUESTED) {
            throw new BadRequestException("Only requested bookings can be accepted");
        }

        booking.setStatus(BookingStatus.ACCEPTED);
        booking.setAcceptedAt(Instant.now());
        booking.setLocalResponseNote(optionalTrim(request.note()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingAcceptedNotification(savedBooking);
        publishBookingAudit(savedBooking.getId(), "ACCEPTED", "Accepted by host", localUserId, "HOST");

        return toResponse(savedBooking, false);
    }

    @Transactional(readOnly = true)
    public BookingResponse getAdminBookingById(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        return toResponse(booking);
    }


    @Transactional
    public BookingResponse declineBooking(UUID localUserId, UUID bookingId, BookingDecisionRequest request) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT ||
                booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new BadRequestException("Use cancellation flow for pending payment or confirmed bookings");
        }

        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(booking.getAvailabilitySlot().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        int updatedBookedCount = Math.max(0, slot.getBookedCount() - seatsConsumed(booking));
        slot.setBookedCount(updatedBookedCount);

        if (slot.getStatus() == AvailabilityStatus.BLOCKED && updatedBookedCount < slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.AVAILABLE);
        }

        booking.setStatus(BookingStatus.DECLINED);
        booking.setDeclinedAt(Instant.now());
        booking.setLocalResponseNote(optionalTrim(request.note()));

        availabilitySlotRepository.save(slot);
        waitlistService.notifyOpenedSpots(slot);
        Booking savedBooking = bookingRepository.save(booking);
        createBookingDeclinedNotification(savedBooking);
        publishBookingAudit(savedBooking.getId(), "DECLINED", "Declined by host", localUserId, "HOST");
        return toResponse(savedBooking, false);
    }

    /** Queue a booking-audit entry; written to the timeline after commit by BookingAuditListener. */
    private void publishBookingAudit(UUID bookingId, String action, String detail, UUID actorUserId, String actorRole) {
        eventPublisher.publishEvent(new BookingAuditEvent(bookingId, action, detail, actorUserId, actorRole));
    }

    /**
     * Refund + cancellation-fee a traveller would incur cancelling this booking, per the active
     * refund policy (100% refund &gt;24h before start, 0% / full fee inside 24h). For an already
     * cancelled booking it reproduces the refund that applied at cancellation time. Powers the
     * confirm popup and the cancelled-booking summary. Owner-scoped.
     */
    @Transactional(readOnly = true)
    public RefundPreviewResponse getTravelerRefundPreview(UUID loggedInUserId, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getLoggedInUser() == null ||
                !booking.getLoggedInUser().getId().equals(loggedInUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }

        Instant asOf = booking.getCancelledAt() != null ? booking.getCancelledAt() : Instant.now();
        RefundCalculationResult calc = paymentService.previewTravelerRefund(booking, asOf);
        BigDecimal refundPercentage = calc.refundPercentage();
        return new RefundPreviewResponse(
                booking.getTotalAmount(),
                calc.refundAmount(),
                refundPercentage,
                BigDecimal.valueOf(100).subtract(refundPercentage),
                booking.getCurrency());
    }

    @Transactional
    public BookingResponse cancelBookingByLoggedInUser(UUID loggedInUserId, UUID bookingId, CancelBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getLoggedInUser() == null ||
                !booking.getLoggedInUser().getId().equals(loggedInUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (!isCancellableBookingStatus(booking.getStatus())) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be cancelled");
        }

        // A traveller may cancel at any time — including inside 24h — so the freed seats go back on
        // sale. The refund is governed by the cancellation-refund policy (100% if >24h before start,
        // 0% / full cancellation fee inside 24h); the client previews it before confirming.
        releaseAvailabilityCapacity(booking);
        handleCancellationPayment(booking, BookingCancellationActor.LOGGED_IN_USER, request.reason());
        booking.setStatus(BookingStatus.CANCELLED_BY_LOGGED_IN_USER);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking);
        publishBookingAudit(savedBooking.getId(), "CANCELLED", "Cancelled by traveller", loggedInUserId, "TRAVELER");
        return toResponse(savedBooking);
    }

    @Transactional
    public BookingResponse cancelBookingByLocal(UUID localUserId, UUID bookingId, CancelBookingRequest request) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (!isCancellableBookingStatus(booking.getStatus())) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be cancelled");
        }

        // A host cannot back out inside 24h of the start. If the booking itself was made less than 24h
        // before start, the host can never cancel it — they should have closed the availability earlier.
        Instant startTime = booking.getAvailabilitySlot().getStartTime();
        if (Duration.between(Instant.now(), startTime).toHours() < HOST_CANCEL_MIN_HOURS) {
            throw new BadRequestException(
                    "A host can only cancel more than 24 hours before the experience starts. " +
                    "Closer to the start time, please contact support.");
        }

        releaseAvailabilityCapacity(booking);
        handleCancellationPayment(booking, BookingCancellationActor.LOCAL, request.reason());
        booking.setStatus(BookingStatus.CANCELLED_BY_LOCAL);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking);
        publishBookingAudit(savedBooking.getId(), "CANCELLED", "Cancelled by host", localUserId, "HOST");
        return toResponse(savedBooking, false);
    }

    /**
     * Host cancels an entire upcoming session (slot): every active booking is refunded and cancelled,
     * then the slot is marked CANCELLED. Allowed only more than 24h before start — inside 24h the session
     * is frozen and the host must contact support. Powers "cancel session" and "block a booked day" in the editor.
     */
    @Transactional
    public int cancelSessionByLocal(UUID localUserId, UUID slotId, String reason) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        if (slot.getLocalProfile() == null || !slot.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Availability slot not found");
        }
        if (slot.getStatus() == AvailabilityStatus.CANCELLED) {
            throw new BadRequestException("This session is already cancelled");
        }

        Instant now = Instant.now();
        Instant startTime = slot.getStartTime();
        if (startTime == null || !startTime.isAfter(now)) {
            throw new BadRequestException("This session has already started");
        }
        if (Duration.between(now, startTime).toHours() < HOST_CANCEL_MIN_HOURS) {
            throw new BadRequestException(
                    "A host can only cancel more than 24 hours before the session starts. " +
                    "Closer to the start time, please contact support.");
        }

        List<Booking> activeBookings = bookingRepository.findByAvailabilitySlotIdAndStatusIn(slotId, BookingStatus.ACTIVE);
        for (Booking booking : activeBookings) {
            handleCancellationPayment(booking, BookingCancellationActor.LOCAL, reason);
            booking.setStatus(BookingStatus.CANCELLED_BY_LOCAL);
            booking.setCancelledAt(now);
            booking.setCancellationReason(optionalTrim(reason));
            createBookingCancelledNotification(bookingRepository.save(booking));
        }

        slot.setBookedCount(0);
        slot.setStatus(AvailabilityStatus.CANCELLED);
        availabilitySlotRepository.save(slot);
        return activeBookings.size();
    }

    private void releaseAvailabilityCapacity(Booking booking) {
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(booking.getAvailabilitySlot().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        int updatedBookedCount = Math.max(0, slot.getBookedCount() - seatsConsumed(booking));
        slot.setBookedCount(updatedBookedCount);

        if (slot.getStatus() == AvailabilityStatus.BLOCKED && updatedBookedCount < slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.AVAILABLE);
        }

        availabilitySlotRepository.save(slot);
        waitlistService.notifyOpenedSpots(slot);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(UUID userId, UUID bookingId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (user.isAdminTier()) {
            return toResponse(booking);
        }

        // SUPPORT is not an all-access role. An agent may only read a booking they have actually
        // been assigned to, i.e. one tied to a conversation they participate in. Without this scope
        // a provisioned SUPPORT account could enumerate bookingIds and read every customer's PII and
        // financial data (IDOR / GDPR confidentiality issue).
        if (user.getRole() == UserRole.SUPPORT &&
                conversationRepository.existsBookingConversationParticipant(bookingId, userId)) {
            return toResponse(booking);
        }

        // Own traveler booking — any role (hosts/admins book as customers too).
        if (booking.getLoggedInUser() != null &&
                booking.getLoggedInUser().getId().equals(userId)) {
            return toResponse(booking);
        }

        if (user.getRole() == UserRole.LOCAL) {
            LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                    .orElseThrow(() -> new BadRequestException("Local profile not found"));

            if (booking.getLocalProfile().getId().equals(localProfile.getId())) {
                return toResponse(booking, false);
            }
        }

        throw new ResourceNotFoundException("Booking not found");
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getAdminBookings(BookingStatus status) {
        List<Booking> bookings = status != null
                ? bookingRepository.findAllForAdminByStatus(status)
                : bookingRepository.findAllForAdmin();
        return bookings.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public BookingResponse createGuestBooking(
            CreateGuestBookingRequest request,
            String ipAddress,
            String userAgent
    ) {
        long totalStart = System.currentTimeMillis();

        long stepStart = System.currentTimeMillis();
        validateGuestConsent(request);
        log.info("GUEST_BOOKING_TIMING validateGuestConsentMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        String normalizedGuestEmail = requiredTrim(request.guestEmail()).toLowerCase(Locale.ROOT);
        log.info("GUEST_BOOKING_TIMING normalizeEmailMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (bookingRepository.existsByGuestEmailIgnoreCaseAndAvailabilitySlotIdAndStatusIn(
                normalizedGuestEmail,
                request.availabilitySlotId(),
                activeBookingStatuses()
        )) {
            throw new BadRequestException("You already have an active guest booking for this slot");
        }
        log.info("GUEST_BOOKING_TIMING duplicateCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        Experience experience = experienceRepository.findById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        log.info("GUEST_BOOKING_TIMING experienceLookupMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience is not available for booking");
        }
        log.info("GUEST_BOOKING_TIMING experienceStatusCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(request.availabilitySlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
        log.info("GUEST_BOOKING_TIMING slotLockLookupMs={}", System.currentTimeMillis() - stepStart);

        AgeBandPricing.AgeBands bands = ageBandPricing.resolve(
                request.adults(), request.teens(), request.children(), request.infants(), request.guestsCount());
        ageBandPricing.validateAgeGate(experience.getMinimumAge(), bands);

        stepStart = System.currentTimeMillis();
        validateSlot(experience, slot, bands.seats());
        log.info("GUEST_BOOKING_TIMING validateSlotMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, experience, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot,
                pricePerGuest, experience.getPrivatePrice(),
                bands.seats(), ageBandPricing.billableUnits(bands));

        int seatsToBook = pricing.seatsBlocked();
        int newBookedCount = slot.getBookedCount() + seatsToBook;
        slot.setBookedCount(newBookedCount);

        if (newBookedCount >= slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.BLOCKED);
        }

        BigDecimal originalAmount = pricing.originalAmount();
        String currency = experience.getCurrency().toUpperCase(Locale.ROOT);
        log.info("GUEST_BOOKING_TIMING updateSlotMemoryMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedDeal appliedDeal = dealService.resolveDealForBooking(experience, pricing.baseForPromo());
        BigDecimal baseAfterDeal = pricing.baseForPromo().subtract(appliedDeal.discountAmount());
        AppliedPromoCodes appliedPromos = promoCodeService.applyPromoCodesForBooking(
                null,
                mergePromoCodes(request.promoCode(), request.promoCodes()),
                normalizedGuestEmail,
                baseAfterDeal,
                currency,
                experience.getId()
        );
        log.info("GUEST_BOOKING_TIMING applyPromoMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedReferralCode appliedReferral = referralService.applyReferralCodeForBooking(
                null,
                request.referralCode(),
                normalizedGuestEmail
        );
        log.info("GUEST_BOOKING_TIMING applyReferralMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        BigDecimal referralDiscount = referralDiscountFor(appliedReferral, appliedPromos.finalAmount());
        BigDecimal totalAmount = appliedPromos.finalAmount().subtract(referralDiscount);

        Booking booking = new Booking();
        booking.setBookingReference(generateUniqueBookingReference());
        booking.setBookingSource(BookingSource.GUEST_USER);

        booking.setGuestFirstName(NameFormatter.requiredName(request.guestFirstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        booking.setGuestLastName(NameFormatter.requiredName(request.guestLastName(), "Last name", NameFormatter.LAST_NAME_MIN));
        booking.setGuestEmail(normalizedGuestEmail);
        booking.setGuestPhone(requiredTrim(request.guestPhone()));
        booking.setGuestEmailVerified(false);
        booking.setGuestPhoneVerified(false);

        booking.setGuestTermsAccepted(Boolean.TRUE.equals(request.acceptedTerms()));
        booking.setGuestSafetyAccepted(false);
        booking.setGuestLiabilityAccepted(false);

        booking.setGuestConsentVersion(requiredTrim(request.consentVersion()));
        booking.setGuestConsentAcceptedAt(Instant.now());
        booking.setGuestConsentIpAddress(optionalTrim(ipAddress));
        booking.setGuestConsentUserAgent(optionalTrim(userAgent));

        booking.setLoggedInUser(null);
        booking.setLocalProfile(experience.getLocalProfile());
        booking.setExperience(experience);
        booking.setAvailabilitySlot(slot);
        booking.setGuestsCount(bands.totalGuests());
        applyBands(booking, bands);
        booking.setPrivateBooking(privateBooking);
        booking.setSeatsBlocked(seatsToBook);
        booking.setStatus(BookingStatus.PENDING_PAYMENT);

        booking.setPricePerGuest(pricePerGuest);
        booking.setOriginalAmount(originalAmount);
        booking.setPrivateDiscountAmount(pricing.privateDiscountAmount());
        booking.setDiscountAmount(appliedPromos.totalDiscount());
        booking.setDealId(appliedDeal.dealId());
        booking.setDealDiscountAmount(appliedDeal.discountAmount());
        booking.setTotalAmount(totalAmount);
        booking.setCurrency(currency);

        applyPromoCodesToBooking(booking, appliedPromos);

        booking.setReferralCode(appliedReferral.referralCode());
        booking.setReferralCodeText(optionalUpper(request.referralCode()));
        booking.setReferralDiscountAmount(referralDiscount);

        booking.setTravelerNote(optionalTrim(request.travelerNote()));
        applyEmergencyContact(booking,
                request.emergencyContactFirstName(),
                request.emergencyContactLastName(),
                request.emergencyContactEmail(),
                request.emergencyContactPhone(),
                request.emergencyContactRelationship());
        booking.setWhatsappOptIn(Boolean.TRUE.equals(request.whatsAppOptIn()));
        booking.setRequestedAt(Instant.now());
        log.info("GUEST_BOOKING_TIMING buildBookingObjectMs={}", System.currentTimeMillis() - stepStart);

        try {
            stepStart = System.currentTimeMillis();
            availabilitySlotRepository.save(slot);
            log.info("GUEST_BOOKING_TIMING saveSlotMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            Booking savedBooking = bookingRepository.save(booking);
            log.info("GUEST_BOOKING_TIMING saveBookingMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            eventPublisher.publishEvent(new BookingCreatedEvent(savedBooking.getId()));
            log.info("GUEST_BOOKING_TIMING publishEventMs={}", System.currentTimeMillis() - stepStart);
            publishBookingAudit(savedBooking.getId(), "BOOKED", "Guest booking created", null, "GUEST");

            stepStart = System.currentTimeMillis();
            BookingResponse response = toResponse(savedBooking);
            log.info("GUEST_BOOKING_TIMING toResponseMs={}", System.currentTimeMillis() - stepStart);

            log.info("GUEST_BOOKING_TIMING totalMs={}", System.currentTimeMillis() - totalStart);

            return response;

        } catch (DataIntegrityViolationException ex) {
            log.warn("GUEST_BOOKING_TIMING failedAfterMs={} reason=data_integrity_violation",
                    System.currentTimeMillis() - totalStart);
            throw new BadRequestException("You already have an active guest booking for this slot");
        }
    }

    /**
     * Admin creates a booking on behalf of a guest, confirmed immediately with no
     * online payment (collected offline). Blocks seats and supports age bands.
     */
    @Transactional
    public BookingResponse createBookingByAdmin(AdminCreateBookingRequest request) {
        Experience experience = experienceRepository.findWithLocalProfileAndUserById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience is not available for booking");
        }

        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(request.availabilitySlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        AgeBandPricing.AgeBands bands = ageBandPricing.resolve(
                request.adults(), request.teens(), request.children(), request.infants(), request.guestsCount());
        ageBandPricing.validateAgeGate(experience.getMinimumAge(), bands);
        validateSlot(experience, slot, bands.seats());

        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, experience, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot,
                pricePerGuest, experience.getPrivatePrice(),
                bands.seats(), ageBandPricing.billableUnits(bands));

        int seatsToBook = pricing.seatsBlocked();
        int newBookedCount = slot.getBookedCount() + seatsToBook;
        slot.setBookedCount(newBookedCount);
        if (newBookedCount >= slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.BLOCKED);
        }

        String currency = experience.getCurrency().toUpperCase(Locale.ROOT);
        Instant now = Instant.now();

        Booking booking = new Booking();
        booking.setBookingReference(generateUniqueBookingReference());
        booking.setBookingSource(BookingSource.ADMIN);
        booking.setGuestFirstName(NameFormatter.requiredName(request.guestFirstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        booking.setGuestLastName(NameFormatter.requiredName(request.guestLastName(), "Last name", NameFormatter.LAST_NAME_MIN));
        booking.setGuestEmail(requiredTrim(request.guestEmail()).toLowerCase(Locale.ROOT));
        booking.setGuestPhone(optionalTrim(request.guestPhone()));
        booking.setLoggedInUser(null);
        booking.setLocalProfile(experience.getLocalProfile());
        booking.setExperience(experience);
        booking.setAvailabilitySlot(slot);
        booking.setGuestsCount(bands.totalGuests());
        applyBands(booking, bands);
        booking.setPrivateBooking(privateBooking);
        booking.setSeatsBlocked(seatsToBook);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPricePerGuest(pricePerGuest);
        booking.setOriginalAmount(pricing.originalAmount());
        booking.setPrivateDiscountAmount(pricing.privateDiscountAmount());
        booking.setDiscountAmount(BigDecimal.ZERO);
        booking.setTotalAmount(pricing.baseForPromo());
        booking.setCurrency(currency);
        booking.setTravelerNote(optionalTrim(request.note()));
        booking.setRequestedAt(now);
        booking.setAcceptedAt(now);

        availabilitySlotRepository.save(slot);
        Booking saved = bookingRepository.save(booking);
        bookingConfirmationNotifier.sendConfirmation(saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public BookingResponse lookupGuestBooking(GuestBookingLookupRequest request) {
        String normalizedReference = requiredTrim(request.bookingReference()).toUpperCase(Locale.ROOT);
        String normalizedEmail = requiredTrim(request.guestEmail()).toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));

        if (booking.getBookingSource() != BookingSource.GUEST_USER) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        return toResponse(booking);
    }

    private void createBookingAcceptedNotification(Booking booking) {
        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_ACCEPTED,
                    "Your booking was accepted",
                    "Your booking has been accepted: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_ACCEPTED:TRAVELER:" + booking.getId()
            );
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.BOOKING_ACCEPTED,
                    "Your guest booking was accepted",
                    "Your guest booking has been accepted. Reference: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_ACCEPTED:GUEST:" + booking.getId() + ":" + booking.getGuestEmail()
            );
        }
    }

    private void createBookingDeclinedNotification(Booking booking) {
        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_DECLINED,
                    "Your booking was declined",
                    "Your booking has been declined: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_DECLINED:TRAVELER:" + booking.getId()
            );
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.BOOKING_DECLINED,
                    "Your guest booking was declined",
                    "Your guest booking has been declined. Reference: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_DECLINED:GUEST:" + booking.getId() + ":" + booking.getGuestEmail()
            );
        }
    }

    @Transactional
    public BookingResponse cancelBookingByAdmin(UUID bookingId, AdminCancelBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!isCancellableBookingStatus(booking.getStatus())) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be cancelled");
        }

        releaseAvailabilityCapacity(booking);

        // Default: refund per the active cancellation-refund policy. An admin may override with an
        // explicit percentage or amount (amount wins when both are supplied).
        BigDecimal overridePercentage = resolveAdminRefundOverridePercentage(booking, request);
        paymentService.handleBookingCancellationPayment(
                booking, BookingCancellationActor.ADMIN, request.reason(), overridePercentage);

        booking.setStatus(BookingStatus.CANCELLED_BY_ADMIN);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking, request.shouldNotifyGuest(), request.shouldNotifyHost());

        return toResponse(savedBooking);
    }

    /** Translates an admin refund override (percentage or explicit amount) into a 0–100 percentage, or null for policy. */
    private BigDecimal resolveAdminRefundOverridePercentage(Booking booking, AdminCancelBookingRequest request) {
        if (request.refundAmount() != null) {
            BigDecimal total = booking.getTotalAmount();
            if (total == null || total.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return request.refundAmount()
                    .max(BigDecimal.ZERO)
                    .min(total)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 4, RoundingMode.HALF_UP);
        }
        if (request.refundPercentage() != null) {
            return request.refundPercentage()
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.valueOf(100));
        }
        return null;
    }

    /**
     * Admin edit of a booking's guest contact + notes (partial update). Null fields are unchanged;
     * contact fields only apply when non-blank (they can be corrected, never cleared, so the DB
     * guest-contact constraints hold); a blank note clears that note. Allowed on any booking so
     * records can be corrected after the fact.
     */
    @Transactional
    public BookingResponse updateBookingDetailsByAdmin(UUID bookingId, AdminUpdateBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (request.guestFirstName() != null && !request.guestFirstName().isBlank()) {
            booking.setGuestFirstName(NameFormatter.requiredName(request.guestFirstName(), "First name", NameFormatter.FIRST_NAME_MIN));
        }
        if (request.guestLastName() != null && !request.guestLastName().isBlank()) {
            booking.setGuestLastName(NameFormatter.requiredName(request.guestLastName(), "Last name", NameFormatter.LAST_NAME_MIN));
        }
        if (request.guestEmail() != null && !request.guestEmail().isBlank()) {
            booking.setGuestEmail(requiredTrim(request.guestEmail()).toLowerCase(Locale.ROOT));
        }
        if (request.guestPhone() != null && !request.guestPhone().isBlank()) {
            booking.setGuestPhone(requiredTrim(request.guestPhone()));
        }
        if (request.travelerNote() != null) {
            booking.setTravelerNote(optionalTrim(request.travelerNote()));
        }
        if (request.localResponseNote() != null) {
            booking.setLocalResponseNote(optionalTrim(request.localResponseNote()));
        }
        if (request.touchesEmergencyContact()) {
            boolean allBlank = optionalTrim(request.emergencyContactFirstName()) == null
                    && optionalTrim(request.emergencyContactLastName()) == null
                    && optionalTrim(request.emergencyContactEmail()) == null
                    && optionalTrim(request.emergencyContactPhone()) == null
                    && optionalTrim(request.emergencyContactRelationship()) == null;
            if (allBlank) {
                booking.setEmergencyContactFirstName(null);
                booking.setEmergencyContactLastName(null);
                booking.setEmergencyContactEmail(null);
                booking.setEmergencyContactPhone(null);
                booking.setEmergencyContactRelationship(null);
            } else {
                applyEmergencyContact(booking,
                        request.emergencyContactFirstName(),
                        request.emergencyContactLastName(),
                        request.emergencyContactEmail(),
                        request.emergencyContactPhone(),
                        request.emergencyContactRelationship());
            }
        }

        return toResponse(bookingRepository.save(booking));
    }

    /** Admin sets/clears the booking's attendance (no-show) verdict. {@code NONE} clears it. */
    @Transactional
    public BookingResponse setBookingAttendanceByAdmin(UUID bookingId, AdminSetAttendanceRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        AttendanceOutcome outcome = request.outcome();
        booking.setAttendanceOutcome(outcome);
        booking.setNoShowMarkedAt(outcome == AttendanceOutcome.NONE ? null : Instant.now());

        return toResponse(bookingRepository.save(booking));
    }

    /** Admin re-sends the traveller-facing booking confirmation (email/in-app/WhatsApp). */
    @Transactional
    public BookingResponse resendBookingConfirmationByAdmin(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only confirmed bookings have a confirmation to resend");
        }

        bookingConfirmationNotifier.sendConfirmation(booking);
        return toResponse(booking);
    }

    /**
     * Admin marks a confirmed booking as completed (e.g. the experience took place). Unlike the
     * host completion flow this does not require the safety checklist, since it is an admin override.
     */
    @Transactional
    public BookingResponse completeBookingByAdmin(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only confirmed bookings can be completed");
        }

        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCompletedAt(Instant.now());

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCompletedNotification(savedBooking);

        return toResponse(savedBooking);
    }

    /**
     * Admin removes guests / reduces a booking's party size. Recomputes the base amount from
     * the new age bands and scales the existing discount proportionally (exact for percentage
     * promos, never over-charges for fixed ones), then frees the released seats on the slot.
     * Only reductions are allowed — adding guests must go through a fresh, payable booking.
     */
    @Transactional
    public BookingResponse updateBookingPartyByAdmin(UUID bookingId, AdminUpdateBookingPartyRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be edited");
        }
        if (booking.isPrivateBooking()) {
            throw new BadRequestException("Party size is fixed for a private (whole-slot) booking");
        }

        AgeBandPricing.AgeBands bands = ageBandPricing.resolve(
                request.adults(), request.teens(), request.children(), request.infants(), null);
        ageBandPricing.validateAgeGate(booking.getExperience().getMinimumAge(), bands);

        int oldTotal = booking.getGuestsCount();
        int newTotal = bands.totalGuests();
        int newSeats = bands.seats();
        if (newTotal < 1) {
            throw new BadRequestException("A booking must keep at least one guest");
        }
        if (newTotal > oldTotal) {
            throw new BadRequestException("Admin can only reduce party size; create a new booking to add guests");
        }

        BigDecimal newOriginal = booking.getPricePerGuest()
                .multiply(ageBandPricing.billableUnits(bands))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal oldOriginal = booking.getOriginalAmount() != null
                ? booking.getOriginalAmount()
                : booking.getTotalAmount().add(booking.getDiscountAmount());
        BigDecimal newDiscount = (oldOriginal != null && oldOriginal.signum() > 0)
                ? booking.getDiscountAmount().multiply(newOriginal).divide(oldOriginal, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        newDiscount = newDiscount.min(newOriginal);
        BigDecimal newTotalAmount = newOriginal.subtract(newDiscount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        int seatsToRelease = seatsConsumed(booking) - newSeats;
        if (seatsToRelease > 0) {
            AvailabilitySlot slot = availabilitySlotRepository
                    .findByIdForUpdate(booking.getAvailabilitySlot().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
            releaseAvailabilityCapacityFromSlot(slot, seatsToRelease);
            availabilitySlotRepository.save(slot);
        }

        applyBands(booking, bands);
        booking.setGuestsCount(newTotal);
        booking.setSeatsBlocked(newSeats);
        booking.setOriginalAmount(newOriginal);
        booking.setDiscountAmount(newDiscount);
        booking.setTotalAmount(newTotalAmount);

        return toResponse(bookingRepository.save(booking));
    }


    private void createBookingCancelledNotification(Booking booking) {
        createBookingCancelledNotification(booking, true, true);
    }

    /** Cancellation emails; the admin cancel flow can suppress either party's notification. */
    private void createBookingCancelledNotification(Booking booking, boolean notifyGuest, boolean notifyHost) {
        if (notifyHost) {
            notificationService.createEmailNotificationForUser(
                    booking.getLocalProfile().getUser(),
                    NotificationType.BOOKING_CANCELLED,
                    "Booking cancelled",
                    "Booking has been cancelled: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_CANCELLED:LOCAL:" + booking.getId()
            );
        }

        if (!notifyGuest) {
            return;
        }
        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_CANCELLED,
                    "Booking cancelled",
                    "Your booking has been cancelled: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_CANCELLED:TRAVELER:" + booking.getId()
            );
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForUser(
                        booking.getLoggedInUser(),
                        NotificationType.BOOKING_CANCELLED,
                        "Booking cancelled",
                        "Your booking has been cancelled: " + booking.getBookingReference(),
                        whatsAppTemplates.forType(NotificationType.BOOKING_CANCELLED).orElse(null),
                        cancellationWaParams(booking),
                        "BOOKING",
                        booking.getId(),
                        "BOOKING_CANCELLED:TRAVELER:" + booking.getId() + ":WHATSAPP"
                );
            }
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.BOOKING_CANCELLED,
                    "Guest booking cancelled",
                    "Your guest booking has been cancelled. Reference: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_CANCELLED:GUEST:" + booking.getId() + ":" + booking.getGuestEmail()
            );
            if (booking.isWhatsappOptIn()) {
                notificationService.createWhatsAppTemplateNotificationForGuest(
                        booking.getGuestEmail(),
                        booking.getGuestPhone(),
                        NotificationType.BOOKING_CANCELLED,
                        "Guest booking cancelled",
                        "Your guest booking has been cancelled. Reference: " + booking.getBookingReference(),
                        whatsAppTemplates.forType(NotificationType.BOOKING_CANCELLED).orElse(null),
                        cancellationWaParams(booking),
                        "BOOKING",
                        booking.getId(),
                        "BOOKING_CANCELLED:GUEST:" + booking.getId() + ":WHATSAPP"
                );
            }
        }
    }

    /** booking-cancelled template body params: {{1}} first name, {{2}} experience title, {{3}} reference. */
    private List<String> cancellationWaParams(Booking booking) {
        String fullName = booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getFullName() : booking.getGuestName();
        String name = fullName == null || fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
        String title = booking.getExperience() != null && booking.getExperience().getTitle() != null
                ? booking.getExperience().getTitle() : "your experience";
        return List.of(name, title, booking.getBookingReference());
    }

    @Transactional
    public BookingResponse completeBooking(UUID localUserId, UUID bookingId) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only confirmed bookings can be completed");
        }
        validateSafetyChecklistCompleted(booking);
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCompletedAt(Instant.now());

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCompletedNotification(savedBooking);
        publishBookingAudit(savedBooking.getId(), "COMPLETED", "Marked completed by host", localUserId, "HOST");

        return toResponse(savedBooking, false);
    }

    @Transactional
    public BookingResponse rescheduleBookingByLocal(
            UUID localUserId,
            UUID bookingId,
            RescheduleBookingRequest request
    ) {
        LocalProfile localProfile = localProfileRepository.findByUserId(localUserId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Booking not found");
        }

        BookingResponse response = rescheduleBooking(booking, request, false);
        publishBookingAudit(bookingId, "RESCHEDULED", "Rescheduled by host", localUserId, "HOST");
        return response;
    }

    @Transactional
    public BookingResponse rescheduleBookingByAdmin(
            UUID bookingId,
            RescheduleBookingRequest request
    ) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        return rescheduleBooking(booking, request, true);
    }

    private BookingResponse rescheduleBooking(
            Booking booking,
            RescheduleBookingRequest request,
            boolean includeEmergencyContact
    ) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be rescheduled");
        }

        AvailabilitySlot oldSlot = availabilitySlotRepository.findByIdForUpdate(
                booking.getAvailabilitySlot().getId()
        ).orElseThrow(() -> new ResourceNotFoundException("Current availability slot not found"));

        AvailabilitySlot newSlot = availabilitySlotRepository.findByIdForUpdate(
                request.newAvailabilitySlotId()
        ).orElseThrow(() -> new ResourceNotFoundException("New availability slot not found"));

        int seatsToMove = seatsConsumed(booking);

        validateSlot(booking.getExperience(), newSlot, seatsToMove);

        releaseAvailabilityCapacityFromSlot(oldSlot, seatsToMove);

        int newBookedCount = newSlot.getBookedCount() + seatsToMove;
        newSlot.setBookedCount(newBookedCount);

        if (newBookedCount >= newSlot.getCapacity()) {
            newSlot.setStatus(AvailabilityStatus.BLOCKED);
        }

        booking.setAvailabilitySlot(newSlot);
        booking.setLocalResponseNote(optionalTrim(request.reason()));

        availabilitySlotRepository.save(oldSlot);
        availabilitySlotRepository.save(newSlot);
        waitlistService.notifyOpenedSpots(oldSlot);

        Booking savedBooking = bookingRepository.save(booking);
        createBookingRescheduledNotification(savedBooking);

        return toResponse(savedBooking, includeEmergencyContact);
    }


    private void createBookingCompletedNotification(Booking booking) {
        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_COMPLETED,
                    "Your LocalBuddy experience is completed",
                    "Your booking has been completed: " + booking.getBookingReference()
                            + ". You can now leave a review.",
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_COMPLETED:TRAVELER:" + booking.getId()
            );
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.BOOKING_COMPLETED,
                    "Your LocalBuddy guest experience is completed",
                    "Your guest booking has been completed. Reference: " + booking.getBookingReference()
                            + ". You can now leave a review.",
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_COMPLETED:GUEST:" + booking.getId() + ":" + booking.getGuestEmail()
            );
        }
    }

    private void validateGuestConsent(CreateGuestBookingRequest request) {
        if (!Boolean.TRUE.equals(request.acceptedTerms())) {
            throw new BadRequestException("Guest must accept Terms and Conditions");
        }

        String consentVersion = requiredTrim(request.consentVersion());

        if (!ConsentService.CURRENT_CONSENT_VERSION.equals(consentVersion)) {
            throw new BadRequestException("Guest consent version is outdated");
        }
    }

    private void validateSafetyChecklistCompleted(Booking booking) {
        UUID loggedInUserId = booking.getLoggedInUser() != null
                ? booking.getLoggedInUser().getId()
                : null;

        UUID localUserId = booking.getLocalProfile() != null &&
                booking.getLocalProfile().getUser() != null
                ? booking.getLocalProfile().getUser().getId()
                : null;

        if (loggedInUserId != null) {
            boolean travelerCompleted = bookingSafetyChecklistRepository
                    .existsByBookingIdAndUserIdAndCompletedTrue(booking.getId(), loggedInUserId);

            if (!travelerCompleted) {
                throw new BadRequestException("Traveler safety checklist must be completed before completing booking");
            }
        }

        if (localUserId != null) {
            boolean localCompleted = bookingSafetyChecklistRepository
                    .existsByBookingIdAndUserIdAndCompletedTrue(booking.getId(), localUserId);

            if (!localCompleted) {
                throw new BadRequestException("Local safety checklist must be completed before completing booking");
            }
        }
    }

    private Set<BookingStatus> activeBookingStatuses() {
        return Set.of(
                BookingStatus.REQUESTED,
                BookingStatus.ACCEPTED,
                BookingStatus.PENDING_PAYMENT,
                BookingStatus.CONFIRMED
        );
    }

    private boolean isCancellableBookingStatus(BookingStatus status) {
        return status == BookingStatus.PENDING_PAYMENT ||
                status == BookingStatus.CONFIRMED;
    }

    private void handleCancellationPayment(
            Booking booking,
            BookingCancellationActor cancelledBy,
            String reason
    ) {
        paymentService.handleBookingCancellationPayment(booking, cancelledBy, reason);
    }

    private String optionalUpper(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** The referral discount for the referred user, clamped so it never exceeds the amount owed. */
    private BigDecimal referralDiscountFor(AppliedReferralCode appliedReferral, BigDecimal base) {
        if (appliedReferral == null || appliedReferral.referralCode() == null
                || appliedReferral.rewardAmount() == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return appliedReferral.rewardAmount()
                .min(base)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> mergePromoCodes(String single, List<String> multiple) {
        List<String> all = new ArrayList<>();
        if (single != null && !single.trim().isEmpty()) {
            all.add(single);
        }
        if (multiple != null) {
            all.addAll(multiple);
        }
        return all;
    }

    /** Records the applied (stacked) codes on the booking: a primary code for display + a child row per code. */
    private void applyPromoCodesToBooking(Booking booking, AppliedPromoCodes appliedPromos) {
        if (appliedPromos.codes().isEmpty()) {
            return;
        }
        var primary = appliedPromos.codes().get(0).promoCode();
        booking.setPromoCode(primary);
        booking.setPromoCodeText(primary.getCode());

        for (AppliedPromoCode applied : appliedPromos.codes()) {
            BookingPromoCode bookingPromoCode = new BookingPromoCode();
            bookingPromoCode.setBooking(booking);
            bookingPromoCode.setPromoCode(applied.promoCode());
            bookingPromoCode.setCodeText(applied.promoCode().getCode());
            bookingPromoCode.setDiscountAmount(applied.discountAmount());
            booking.getAppliedPromoCodes().add(bookingPromoCode);
        }
    }

    private void createBookingRescheduledNotification(Booking booking) {
        notificationService.createEmailNotificationForUser(
                booking.getLocalProfile().getUser(),
                NotificationType.BOOKING_UPDATED,
                "Booking rescheduled",
                "Booking has been rescheduled: " + booking.getBookingReference(),
                "BOOKING",
                booking.getId(),
                "BOOKING_RESCHEDULED:LOCAL:" + booking.getId()
        );

        if (booking.getLoggedInUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getLoggedInUser(),
                    NotificationType.BOOKING_UPDATED,
                    "Your LocalBuddy booking was rescheduled",
                    "Your booking has been rescheduled: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_RESCHEDULED:TRAVELER:" + booking.getId()
            );
        } else {
            notificationService.createEmailNotificationForGuest(
                    booking.getGuestEmail(),
                    booking.getGuestPhone(),
                    NotificationType.GUEST_BOOKING_CREATED,
                    "Your LocalBuddy guest booking was rescheduled",
                    "Your guest booking has been rescheduled. Reference: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_RESCHEDULED:GUEST:" + booking.getId() + ":" + booking.getGuestEmail()
            );
        }
    }


    private void releaseAvailabilityCapacityFromSlot(AvailabilitySlot slot, int guestsCount) {
        int updatedBookedCount = Math.max(0, slot.getBookedCount() - guestsCount);
        slot.setBookedCount(updatedBookedCount);

        if (slot.getStatus() == AvailabilityStatus.BLOCKED &&
                updatedBookedCount < slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.AVAILABLE);
        }
    }


    private String requiredTrim(String value) {
        return value.trim();
    }
}