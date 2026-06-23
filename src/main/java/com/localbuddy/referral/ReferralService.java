package com.localbuddy.referral;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

@Service
public class ReferralService {

    private static final String CODE_PREFIX = "LB";
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRedemptionRepository referralRedemptionRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRedemptionRepository referralRedemptionRepository,
                           UserRepository userRepository) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRedemptionRepository = referralRedemptionRepository;
        this.userRepository = userRepository;
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
            return new AppliedReferralCode(null);
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

        return new AppliedReferralCode(referralCode);
    }

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
        redemption.setRewardStatus(ReferralRewardStatus.ELIGIBLE);
        redemption.setRewardAmount(null);
        redemption.setRewardCurrency(booking.getCurrency());
        redemption.setRedeemedAt(Instant.now());

        referralRedemptionRepository.save(redemption);

        ReferralCode referralCode = booking.getReferralCode();
        referralCode.setCurrentRedemptions(referralCode.getCurrentRedemptions() + 1);
        referralCodeRepository.save(referralCode);
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