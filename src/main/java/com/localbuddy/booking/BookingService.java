package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.ConsentService;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.promo.AppliedPromoCode;
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
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final String REFERENCE_PREFIX = "LB";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final BigDecimal PRIVATE_DISCOUNT_RATE = new BigDecimal("0.20");
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ExperienceRepository experienceRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final LocalProfileRepository localProfileRepository;
    private final NotificationService notificationService;
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

    public BookingService(BookingRepository bookingRepository,
                          UserRepository userRepository,
                          ExperienceRepository experienceRepository,
                          AvailabilitySlotRepository availabilitySlotRepository,
                          LocalProfileRepository localProfileRepository, NotificationService notificationService, ConsentService consentService, PromoCodeService promoCodeService, ReferralService referralService, BookingSafetyChecklistRepository bookingSafetyChecklistRepository, PaymentService paymentService, TrustSafetyService trustSafetyService, ApplicationEventPublisher eventPublisher, BookingReferenceGenerator bookingReferenceGenerator, WaitlistService waitlistService, AgeBandPricing ageBandPricing, BookingConfirmationNotifier bookingConfirmationNotifier) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.experienceRepository = experienceRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.localProfileRepository = localProfileRepository;
        this.notificationService = notificationService;
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
    }

    @Transactional
    public BookingResponse createBooking(UUID travelerUserId, CreateBookingRequest request) {
        long totalStart = System.currentTimeMillis();

        long stepStart = System.currentTimeMillis();
        User traveler = userRepository.findById(travelerUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));
        log.info("TRAVELER_BOOKING_TIMING userLookupMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (traveler.getRole() != UserRole.TRAVELER) {
            throw new BadRequestException("Only travelers can create bookings");
        }
        log.info("TRAVELER_BOOKING_TIMING roleCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        trustSafetyService.requireUserCanBook(travelerUserId);
        log.info("TRAVELER_BOOKING_TIMING travelerRestrictionCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        consentService.requireTravelerConsents(travelerUserId);
        log.info("TRAVELER_BOOKING_TIMING travelerConsentCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (bookingRepository.existsByTravelerUserIdAndAvailabilitySlotIdAndStatusIn(
                travelerUserId,
                request.availabilitySlotId(),
                activeBookingStatuses()
        )) {
            throw new BadRequestException("You already have an active booking for this slot");
        }
        log.info("TRAVELER_BOOKING_TIMING duplicateCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        Experience experience = experienceRepository.findWithLocalProfileAndUserById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        log.info("TRAVELER_BOOKING_TIMING experienceLookupMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        trustSafetyService.requireUserCanHost(
                experience.getLocalProfile().getUser().getId()
        );
        log.info("TRAVELER_BOOKING_TIMING hostRestrictionCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience is not available for booking");
        }
        log.info("TRAVELER_BOOKING_TIMING experienceStatusCheckMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(request.availabilitySlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
        log.info("TRAVELER_BOOKING_TIMING slotLockLookupMs={}", System.currentTimeMillis() - stepStart);

        AgeBandPricing.AgeBands bands = ageBandPricing.resolve(
                request.adults(), request.teens(), request.children(), request.infants(), request.guestsCount());
        ageBandPricing.validateAgeGate(experience.getMinimumAge(), bands);

        stepStart = System.currentTimeMillis();
        validateSlot(experience, slot, bands.totalGuests());
        log.info("TRAVELER_BOOKING_TIMING validateSlotMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot, pricePerGuest,
                bands.totalGuests(), ageBandPricing.billableUnits(bands));

        int seatsToBook = pricing.seatsBlocked();
        int newBookedCount = slot.getBookedCount() + seatsToBook;
        slot.setBookedCount(newBookedCount);

        if (newBookedCount >= slot.getCapacity()) {
            slot.setStatus(AvailabilityStatus.BLOCKED);
        }

        BigDecimal originalAmount = pricing.originalAmount();
        String currency = experience.getCurrency().toUpperCase(Locale.ROOT);
        log.info("TRAVELER_BOOKING_TIMING updateSlotMemoryMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedPromoCode appliedPromo = promoCodeService.applyPromoCodeForBooking(
                travelerUserId,
                request.promoCode(),
                null,
                pricing.baseForPromo(),
                currency
        );
        log.info("TRAVELER_BOOKING_TIMING applyPromoMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        AppliedReferralCode appliedReferral = referralService.applyReferralCodeForBooking(
                travelerUserId,
                request.referralCode(),
                null
        );
        log.info("TRAVELER_BOOKING_TIMING applyReferralMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        BigDecimal totalAmount = appliedPromo.finalAmount();

        Booking booking = new Booking();
        booking.setBookingReference(generateUniqueBookingReference());
        booking.setTravelerUser(traveler);
        booking.setBookingSource(BookingSource.LOGGED_IN_USER);
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
        booking.setDiscountAmount(appliedPromo.discountAmount());
        booking.setTotalAmount(totalAmount);
        booking.setCurrency(currency);

        booking.setPromoCode(appliedPromo.promoCode());
        booking.setPromoCodeText(optionalUpper(request.promoCode()));

        booking.setReferralCode(appliedReferral.referralCode());
        booking.setReferralCodeText(optionalUpper(request.referralCode()));

        booking.setTravelerNote(optionalTrim(request.travelerNote()));
        booking.setRequestedAt(Instant.now());
        log.info("TRAVELER_BOOKING_TIMING buildBookingObjectMs={}", System.currentTimeMillis() - stepStart);

        try {
            stepStart = System.currentTimeMillis();
            availabilitySlotRepository.save(slot);
            log.info("TRAVELER_BOOKING_TIMING saveSlotMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            Booking savedBooking = bookingRepository.save(booking);
            log.info("TRAVELER_BOOKING_TIMING saveBookingMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            eventPublisher.publishEvent(new BookingCreatedEvent(savedBooking.getId()));
            log.info("TRAVELER_BOOKING_TIMING publishEventMs={}", System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            BookingResponse response = toResponse(savedBooking);
            log.info("TRAVELER_BOOKING_TIMING toResponseMs={}", System.currentTimeMillis() - stepStart);

            log.info("TRAVELER_BOOKING_TIMING totalMs={}", System.currentTimeMillis() - totalStart);

            return response;

        } catch (DataIntegrityViolationException ex) {
            log.warn("TRAVELER_BOOKING_TIMING failedAfterMs={} reason=data_integrity_violation",
                    System.currentTimeMillis() - totalStart);

            throw new BadRequestException("You already have an active booking for this slot");
        }
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        if (user.getRole() == UserRole.TRAVELER) {
            return bookingRepository.findByTravelerUserIdOrderByRequestedAtDesc(userId)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        if (user.getRole() == UserRole.LOCAL) {
            LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                    .orElseThrow(() -> new BadRequestException("Local profile not found"));

            return bookingRepository.findByLocalProfileIdOrderByRequestedAtDesc(localProfile.getId())
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return bookingRepository.findAll()
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
     * <p>For a private buyout the price is based on the full slot
     * ({@code capacity x pricePerGuest}) with a 20% private-tour discount applied;
     * promo/referral discounts then apply on top of that discounted base. For a
     * normal booking the base is simply {@code guestsCount x pricePerGuest}.
     */
    private BookingPricing computePricing(boolean privateBooking,
                                          AvailabilitySlot slot,
                                          BigDecimal pricePerGuest,
                                          int seats,
                                          BigDecimal billableUnits) {
        if (privateBooking) {
            BigDecimal originalAmount = pricePerGuest
                    .multiply(BigDecimal.valueOf(slot.getCapacity()))
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal privateDiscountAmount = originalAmount
                    .multiply(PRIVATE_DISCOUNT_RATE)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal baseForPromo = originalAmount.subtract(privateDiscountAmount);

            return new BookingPricing(originalAmount, privateDiscountAmount, baseForPromo, slot.getCapacity());
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

    private void requirePrivateBookingAllowed(boolean privateBooking, AvailabilitySlot slot) {
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

        if (!slot.getStartTime().isAfter(Instant.now())) {
            throw new BadRequestException("Cannot book a past slot");
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
        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getTravelerUser() != null ? booking.getTravelerUser().getId() : null,
                booking.getGuestName(),
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
                booking.getSeatsBlocked()
        );
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

        return toResponse(savedBooking);
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
        return toResponse(savedBooking);
    }

    @Transactional
    public BookingResponse cancelBookingByTraveler(UUID travelerUserId, UUID bookingId, CancelBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getTravelerUser() == null ||
                !booking.getTravelerUser().getId().equals(travelerUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (!isCancellableBookingStatus(booking.getStatus())) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be cancelled");
        }

        releaseAvailabilityCapacity(booking);
        handleCancellationPayment(booking, BookingCancellationActor.TRAVELER, request.reason());
        booking.setStatus(BookingStatus.CANCELLED_BY_TRAVELER);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking);
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

        releaseAvailabilityCapacity(booking);
        handleCancellationPayment(booking, BookingCancellationActor.LOCAL, request.reason());        booking.setStatus(BookingStatus.CANCELLED_BY_LOCAL);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking);
        return toResponse(savedBooking);
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

        if (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPPORT) {
            return toResponse(booking);
        }

        if (user.getRole() == UserRole.TRAVELER &&
                booking.getTravelerUser() != null &&
                booking.getTravelerUser().getId().equals(userId)) {
            return toResponse(booking);
        }

        if (user.getRole() == UserRole.LOCAL) {
            LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                    .orElseThrow(() -> new BadRequestException("Local profile not found"));

            if (booking.getLocalProfile().getId().equals(localProfile.getId())) {
                return toResponse(booking);
            }
        }

        throw new ResourceNotFoundException("Booking not found");
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getAdminBookings(BookingStatus status) {
        if (status != null) {
            return bookingRepository.findByStatusOrderByRequestedAtDesc(status)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return bookingRepository.findAll()
                .stream()
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
        validateSlot(experience, slot, bands.totalGuests());
        log.info("GUEST_BOOKING_TIMING validateSlotMs={}", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot, pricePerGuest,
                bands.totalGuests(), ageBandPricing.billableUnits(bands));

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
        AppliedPromoCode appliedPromo = promoCodeService.applyPromoCodeForBooking(
                null,
                request.promoCode(),
                normalizedGuestEmail,
                pricing.baseForPromo(),
                currency
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
        BigDecimal totalAmount = appliedPromo.finalAmount();

        Booking booking = new Booking();
        booking.setBookingReference(generateUniqueBookingReference());
        booking.setBookingSource(BookingSource.GUEST);

        booking.setGuestName(requiredTrim(request.guestName()));
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

        booking.setTravelerUser(null);
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
        booking.setDiscountAmount(appliedPromo.discountAmount());
        booking.setTotalAmount(totalAmount);
        booking.setCurrency(currency);

        booking.setPromoCode(appliedPromo.promoCode());
        booking.setPromoCodeText(optionalUpper(request.promoCode()));

        booking.setReferralCode(appliedReferral.referralCode());
        booking.setReferralCodeText(optionalUpper(request.referralCode()));

        booking.setTravelerNote(optionalTrim(request.travelerNote()));
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
        validateSlot(experience, slot, bands.totalGuests());

        boolean privateBooking = Boolean.TRUE.equals(request.privateBooking());
        requirePrivateBookingAllowed(privateBooking, slot);

        BigDecimal pricePerGuest = experience.getPriceAmount();
        BookingPricing pricing = computePricing(privateBooking, slot, pricePerGuest,
                bands.totalGuests(), ageBandPricing.billableUnits(bands));

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
        booking.setGuestName(requiredTrim(request.guestName()));
        booking.setGuestEmail(requiredTrim(request.guestEmail()).toLowerCase(Locale.ROOT));
        booking.setGuestPhone(optionalTrim(request.guestPhone()));
        booking.setTravelerUser(null);
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

        if (booking.getBookingSource() != BookingSource.GUEST) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        return toResponse(booking);
    }

    private void createBookingAcceptedNotification(Booking booking) {
        if (booking.getTravelerUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getTravelerUser(),
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
        if (booking.getTravelerUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getTravelerUser(),
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
    public BookingResponse cancelBookingByAdmin(UUID bookingId, CancelBookingRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!isCancellableBookingStatus(booking.getStatus())) {
            throw new BadRequestException("Only pending payment or confirmed bookings can be cancelled");
        }

        releaseAvailabilityCapacity(booking);
        handleCancellationPayment(booking, BookingCancellationActor.ADMIN, request.reason());

        booking.setStatus(BookingStatus.CANCELLED_BY_ADMIN);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(optionalTrim(request.reason()));

        Booking savedBooking = bookingRepository.save(booking);
        createBookingCancelledNotification(savedBooking);

        return toResponse(savedBooking);
    }


    private void createBookingCancelledNotification(Booking booking) {
        notificationService.createEmailNotificationForUser(
                booking.getLocalProfile().getUser(),
                NotificationType.BOOKING_CANCELLED,
                "Booking cancelled",
                "Booking has been cancelled: " + booking.getBookingReference(),
                "BOOKING",
                booking.getId(),
                "BOOKING_CANCELLED:LOCAL:" + booking.getId()
        );

        if (booking.getTravelerUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getTravelerUser(),
                    NotificationType.BOOKING_CANCELLED,
                    "Booking cancelled",
                    "Your booking has been cancelled: " + booking.getBookingReference(),
                    "BOOKING",
                    booking.getId(),
                    "BOOKING_CANCELLED:TRAVELER:" + booking.getId()
            );
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
        }
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

        return toResponse(savedBooking);
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

        return rescheduleBooking(booking, request);
    }

    @Transactional
    public BookingResponse rescheduleBookingByAdmin(
            UUID bookingId,
            RescheduleBookingRequest request
    ) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        return rescheduleBooking(booking, request);
    }

    private BookingResponse rescheduleBooking(
            Booking booking,
            RescheduleBookingRequest request
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

        return toResponse(savedBooking);
    }


    private void createBookingCompletedNotification(Booking booking) {
        if (booking.getTravelerUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getTravelerUser(),
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
        UUID travelerUserId = booking.getTravelerUser() != null
                ? booking.getTravelerUser().getId()
                : null;

        UUID localUserId = booking.getLocalProfile() != null &&
                booking.getLocalProfile().getUser() != null
                ? booking.getLocalProfile().getUser().getId()
                : null;

        if (travelerUserId != null) {
            boolean travelerCompleted = bookingSafetyChecklistRepository
                    .existsByBookingIdAndUserIdAndCompletedTrue(booking.getId(), travelerUserId);

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

        if (booking.getTravelerUser() != null) {
            notificationService.createEmailNotificationForUser(
                    booking.getTravelerUser(),
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