package com.localbuddy.referral;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ReferralService {

    private static final String CODE_PREFIX = "LB";
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    /** Booking states that permanently void a pending referral reward. */
    private static final Set<BookingStatus> CANCELLED_STATES = Set.of(
            BookingStatus.CANCELLED_BY_LOGGED_IN_USER,
            BookingStatus.CANCELLED_BY_LOCAL,
            BookingStatus.CANCELLED_BY_ADMIN,
            BookingStatus.CANCELLED_MINIMUM_NOT_MET,
            BookingStatus.EXPIRED,
            BookingStatus.DECLINED
    );

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRedemptionRepository referralRedemptionRepository;
    private final UserRepository userRepository;
    private final ReferralRewardConfigService rewardConfigService;
    private final PromoCodeService promoCodeService;
    private final NotificationService notificationService;
    private final int voucherExpiryDays;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRedemptionRepository referralRedemptionRepository,
                           UserRepository userRepository,
                           ReferralRewardConfigService rewardConfigService,
                           PromoCodeService promoCodeService,
                           NotificationService notificationService,
                           @Value("${app.referral.reward-voucher-expiry-days:90}") int voucherExpiryDays) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRedemptionRepository = referralRedemptionRepository;
        this.userRepository = userRepository;
        this.rewardConfigService = rewardConfigService;
        this.promoCodeService = promoCodeService;
        this.notificationService = notificationService;
        this.voucherExpiryDays = voucherExpiryDays;
    }

    @Transactional
    public ReferralCodeResponse getOrCreateMyReferralCode(UUID userId) {
        return referralCodeRepository.findByOwnerUserId(userId)
                .map(this::toResponse)
                .orElseGet(() -> createReferralCodeForUser(userId));
    }

    @Transactional(readOnly = true)
    public ReferralCodeResponse getMyReferralCode(UUID userId) {
        ReferralCode referralCode = referralCodeRepository.findByOwnerUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Referral code not found"));

        return toResponse(referralCode);
    }

    @Transactional(readOnly = true)
    public ValidateReferralCodeResponse validateReferralCode(
            UUID currentUserId,
            ValidateReferralCodeRequest request
    ) {
        String normalizedCode = normalizeCode(request.code());

        ReferralCode referralCode = referralCodeRepository.findByCodeIgnoreCase(normalizedCode)
                .orElse(null);

        if (referralCode == null) {
            return invalid(normalizedCode, "Referral code not found");
        }

        if (!referralCode.isActive()) {
            return invalid(normalizedCode, "Referral code is inactive");
        }

        if (referralCode.getMaxRedemptions() != null &&
                referralRedemptionRepository.countByReferralCodeId(referralCode.getId()) >= referralCode.getMaxRedemptions()) {
            return invalid(normalizedCode, "Referral code redemption limit reached");
        }

        // Per-referrer monthly cap: how many (non-cancelled) redemptions this code has
        // had since the start of the current month.
        int monthlyCap = rewardConfigService.effectiveMonthlyCap();
        long thisMonth = referralRedemptionRepository
                .countByReferralCodeIdAndRewardStatusNotAndRedeemedAtGreaterThanEqual(
                        referralCode.getId(), ReferralRewardStatus.CANCELLED, startOfCurrentMonth());
        if (thisMonth >= monthlyCap) {
            return invalid(normalizedCode, "This referral code has reached its monthly limit");
        }

        if (currentUserId != null) {
            if (referralCode.getOwnerUser().getId().equals(currentUserId)) {
                return invalid(normalizedCode, "You cannot use your own referral code");
            }

            if (referralRedemptionRepository.existsByReferralCodeIdAndReferredUserId(referralCode.getId(), currentUserId)) {
                return invalid(normalizedCode, "Referral code already used by this user");
            }
        }

        String guestEmail = optionalTrim(request.guestEmail());
        if (currentUserId == null && guestEmail != null &&
                referralRedemptionRepository.existsByReferralCodeIdAndReferredGuestEmailIgnoreCase(referralCode.getId(), guestEmail)) {
            return invalid(normalizedCode, "Referral code already used by this guest email");
        }

        return new ValidateReferralCodeResponse(
                true,
                referralCode.getId(),
                referralCode.getOwnerUser().getId(),
                referralCode.getCode(),
                rewardConfigService.effectiveRewardAmount(Instant.now()),
                "Referral code is valid"
        );
    }

    private ReferralCodeResponse createReferralCodeForUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        ReferralCode referralCode = new ReferralCode();
        referralCode.setOwnerUser(user);
        referralCode.setCode(generateUniqueReferralCode());
        referralCode.setActive(true);
        referralCode.setMaxRedemptions(null);
        referralCode.setCurrentRedemptions(0);

        return toResponse(referralCodeRepository.save(referralCode));
    }

    private String generateUniqueReferralCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = CODE_PREFIX + randomCode();
            if (!referralCodeRepository.existsByCodeIgnoreCase(code)) {
                return code;
            }
        }

        throw new BadRequestException("Unable to generate referral code");
    }

    @Transactional(readOnly = true)
    public AppliedReferralCode applyReferralCodeForBooking(
            UUID currentUserId,
            String referralCodeText,
            String guestEmail
    ) {
        if (referralCodeText == null || referralCodeText.trim().isEmpty()) {
            return AppliedReferralCode.none();
        }

        String normalizedCode = normalizeCode(referralCodeText);

        ReferralCode referralCode = referralCodeRepository.findByCodeIgnoreCase(normalizedCode)
                .orElseThrow(() -> new BadRequestException("Referral code not found"));

        ValidateReferralCodeResponse validation = validateReferralCode(
                currentUserId,
                new ValidateReferralCodeRequest(normalizedCode, guestEmail)
        );

        if (!validation.valid()) {
            throw new BadRequestException(validation.message());
        }

        return new AppliedReferralCode(referralCode, rewardConfigService.effectiveRewardAmount(Instant.now()));
    }

    /**
     * Records the referral redemption when a booking is paid, as {@link ReferralRewardStatus#PENDING}.
     * The referrer's reward is only granted later, when the booking COMPLETES (see
     * {@link #settleReferralRewards()}); a cancellation before then voids it.
     */
    @Transactional
    public void redeemReferralCodeForPaidBooking(Booking booking) {
        if (booking.getReferralCode() == null) {
            return;
        }

        boolean alreadyRedeemed = referralRedemptionRepository
                .existsByBookingId(booking.getId());

        if (alreadyRedeemed) {
            return;
        }

        ReferralRedemption redemption = new ReferralRedemption();
        redemption.setReferralCode(booking.getReferralCode());
        redemption.setReferredUser(booking.getLoggedInUser());
        redemption.setReferredGuestEmail(booking.getGuestEmail());
        redemption.setBooking(booking);
        redemption.setRewardStatus(ReferralRewardStatus.PENDING);
        // The referrer's reward equals the discount the referred user actually received.
        redemption.setRewardAmount(booking.getReferralDiscountAmount());
        redemption.setRewardCurrency(booking.getCurrency());
        redemption.setRedeemedAt(Instant.now());

        referralRedemptionRepository.save(redemption);

        ReferralCode referralCode = booking.getReferralCode();
        referralCode.setCurrentRedemptions(referralCode.getCurrentRedemptions() + 1);
        referralCodeRepository.save(referralCode);
    }

    /**
     * Settles PENDING referral redemptions by booking outcome: a COMPLETED booking mints
     * the referrer's reward voucher (status → PROCESSED); a cancelled/expired booking voids
     * it (status → CANCELLED). Idempotent — only PENDING rows are considered. Driven by
     * {@code ReferralRewardProcessor}.
     */
    @Transactional
    public void settleReferralRewards() {
        for (ReferralRedemption redemption : referralRedemptionRepository.findByRewardStatus(ReferralRewardStatus.PENDING)) {
            Booking booking = redemption.getBooking();
            if (booking == null) {
                continue;
            }
            BookingStatus status = booking.getStatus();
            if (status == BookingStatus.COMPLETED) {
                grantReferrerReward(redemption);
            } else if (CANCELLED_STATES.contains(status)) {
                voidRedemption(redemption);
            }
            // Otherwise the booking is still in-flight — leave the redemption PENDING.
        }
    }

    private void grantReferrerReward(ReferralRedemption redemption) {
        ReferralCode code = redemption.getReferralCode();
        User owner = code != null ? code.getOwnerUser() : null;
        BigDecimal amount = redemption.getRewardAmount();

        if (owner != null && amount != null && amount.signum() > 0) {
            String currency = redemption.getRewardCurrency() != null ? redemption.getRewardCurrency() : "EUR";
            String voucherCode = promoCodeService.mintReferralRewardVoucher(
                    owner.getId(), amount, currency, voucherExpiryDays);
            notificationService.createEmailNotificationForUser(
                    owner,
                    NotificationType.SYSTEM_ALERT,
                    "You earned a referral reward",
                    "Your referral was completed. Use code " + voucherCode + " for "
                            + currency + " " + amount.toPlainString() + " off your next booking.",
                    "REFERRAL",
                    redemption.getId(),
                    "REFERRAL_REWARD:" + redemption.getId()
            );
        }

        redemption.setRewardStatus(ReferralRewardStatus.PROCESSED);
        redemption.setRewardProcessedAt(Instant.now());
        referralRedemptionRepository.save(redemption);
    }

    private void voidRedemption(ReferralRedemption redemption) {
        redemption.setRewardStatus(ReferralRewardStatus.CANCELLED);
        redemption.setRewardProcessedAt(Instant.now());
        referralRedemptionRepository.save(redemption);

        ReferralCode code = redemption.getReferralCode();
        if (code != null && code.getCurrentRedemptions() != null && code.getCurrentRedemptions() > 0) {
            code.setCurrentRedemptions(code.getCurrentRedemptions() - 1);
            referralCodeRepository.save(code);
        }
    }

    private Instant startOfCurrentMonth() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CHARACTERS.charAt(secureRandom.nextInt(CHARACTERS.length())));
        }

        return builder.toString();
    }

    private ValidateReferralCodeResponse invalid(String code, String message) {
        return new ValidateReferralCodeResponse(
                false,
                null,
                null,
                code,
                BigDecimal.ZERO,
                message
        );
    }

    private ReferralCodeResponse toResponse(ReferralCode referralCode) {
        return new ReferralCodeResponse(
                referralCode.getId(),
                referralCode.getOwnerUser().getId(),
                referralCode.getCode(),
                referralCode.isActive(),
                referralCode.getMaxRedemptions(),
                referralCode.getCurrentRedemptions(),
                referralCode.getCreatedAt(),
                referralCode.getUpdatedAt()
        );
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BadRequestException("Referral code is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
