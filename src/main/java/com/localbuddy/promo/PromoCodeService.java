package com.localbuddy.promo;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingPromoCode;
import java.time.Instant;

@Service
public class PromoCodeService {

    private final PromoCodeRepository promoCodeRepository;
    private final PromoCodeRedemptionRepository promoCodeRedemptionRepository;

    public PromoCodeService(PromoCodeRepository promoCodeRepository,
                            PromoCodeRedemptionRepository promoCodeRedemptionRepository) {
        this.promoCodeRepository = promoCodeRepository;
        this.promoCodeRedemptionRepository = promoCodeRedemptionRepository;
    }

    @Transactional
    public PromoCodeResponse createPromoCode(CreatePromoCodeRequest request) {
        String normalizedCode = normalizeCode(request.code());

        if (promoCodeRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new BadRequestException("Promo code already exists");
        }

        validatePromoConfig(request);

        PromoCode promoCode = new PromoCode();
        promoCode.setCode(normalizedCode);
        promoCode.setDescription(optionalTrim(request.description()));
        promoCode.setDiscountType(request.discountType());
        promoCode.setDiscountValue(request.discountValue());
        promoCode.setCurrency(optionalUpper(request.currency()));
        promoCode.setMaxDiscountAmount(request.maxDiscountAmount());
        promoCode.setMinBookingAmount(request.minBookingAmount());
        promoCode.setMaxTotalRedemptions(request.maxTotalRedemptions());
        promoCode.setMaxRedemptionsPerUser(request.maxRedemptionsPerUser());
        promoCode.setStartsAt(request.startsAt());
        promoCode.setExpiresAt(request.expiresAt());
        promoCode.setActive(request.active() == null || request.active());
        promoCode.setDiscountBearer(request.discountBearer() != null ? request.discountBearer() : DiscountBearer.HOST);
        promoCode.setPlatformSharePercentage(request.platformSharePercentage());
        promoCode.setIssuedToUserId(request.issuedToUserId());
        promoCode.setIssuedToEmail(optionalTrim(request.issuedToEmail()));
        promoCode.setCombinable(request.combinable() != null && request.combinable());
        promoCode.setExperienceIds(request.experienceIds() == null
                ? new java.util.HashSet<>()
                : new java.util.HashSet<>(request.experienceIds()));

        return toResponse(promoCodeRepository.save(promoCode));
    }

    @Transactional(readOnly = true)
    public List<PromoCodeResponse> listPromoCodes() {
        return promoCodeRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PromoCodeResponse getPromoCode(UUID promoCodeId) {
        PromoCode promoCode = promoCodeRepository.findById(promoCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Promo code not found"));

        return toResponse(promoCode);
    }

    @Transactional(readOnly = true)
    public ValidatePromoCodeResponse validatePromoCode(UUID userId, ValidatePromoCodeRequest request) {
        String normalizedCode = normalizeCode(request.code());
        String requestCurrency = request.currency().trim().toUpperCase(Locale.ROOT);
        BigDecimal bookingAmount = request.bookingAmount().setScale(2, RoundingMode.HALF_UP);

        PromoCode promoCode = promoCodeRepository.findByCodeIgnoreCase(normalizedCode)
                .orElse(null);

        if (promoCode == null) {
            return invalid(normalizedCode, bookingAmount, "Promo code not found");
        }

        String validationError = getValidationError(promoCode, userId, optionalTrim(request.guestEmail()), bookingAmount, requestCurrency, request.experienceId());

        if (validationError != null) {
            return invalid(normalizedCode, bookingAmount, validationError);
        }

        BigDecimal discountAmount = calculateDiscountAmount(promoCode, bookingAmount);
        BigDecimal finalAmount = bookingAmount.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        return new ValidatePromoCodeResponse(
                true,
                promoCode.getId(),
                promoCode.getCode(),
                promoCode.getDiscountType(),
                promoCode.getDiscountValue(),
                discountAmount,
                finalAmount,
                "Promo code applied"
        );
    }

    private String getValidationError(PromoCode promoCode,
                                      UUID userId,
                                      String guestEmail,
                                      BigDecimal bookingAmount,
                                      String currency,
                                      UUID experienceId) {
        Instant now = Instant.now();

        if (!promoCode.isActive()) {
            return "Promo code is inactive";
        }

        // Experience scoping: a code limited to specific experiences is valid only on those.
        if (promoCode.getExperienceIds() != null && !promoCode.getExperienceIds().isEmpty()) {
            if (experienceId == null || !promoCode.getExperienceIds().contains(experienceId)) {
                return "This promo code isn't valid for this experience";
            }
        }

        if (promoCode.getStartsAt() != null && now.isBefore(promoCode.getStartsAt())) {
            return "Promo code is not active yet";
        }

        if (promoCode.getExpiresAt() != null && now.isAfter(promoCode.getExpiresAt())) {
            return "Promo code has expired";
        }

        // Voucher targeting: a code issued to a specific customer is usable only by them.
        if (promoCode.getIssuedToUserId() != null) {
            if (userId == null || !promoCode.getIssuedToUserId().equals(userId)) {
                return "This code can only be used by the customer it was issued to";
            }
        } else if (promoCode.getIssuedToEmail() != null) {
            if (guestEmail == null || !promoCode.getIssuedToEmail().equalsIgnoreCase(guestEmail)) {
                return "This code can only be used by the customer it was issued to";
            }
        }

        if (promoCode.getCurrency() != null && !promoCode.getCurrency().equalsIgnoreCase(currency)) {
            return "Promo code is not valid for this currency";
        }

        if (promoCode.getMinBookingAmount() != null && bookingAmount.compareTo(promoCode.getMinBookingAmount()) < 0) {
            return "Booking amount is below minimum required amount";
        }

        if (promoCode.getMaxTotalRedemptions() != null &&
                promoCode.getCurrentRedemptions() >= promoCode.getMaxTotalRedemptions()) {
            return "Promo code redemption limit reached";
        }

        if (promoCode.getMaxRedemptionsPerUser() != null) {
            if (userId != null) {
                if (promoCodeRedemptionRepository.countByPromoCodeIdAndUserId(promoCode.getId(), userId)
                        >= promoCode.getMaxRedemptionsPerUser()) {
                    return "Promo code already used by this user";
                }
            } else if (guestEmail != null) {
                if (promoCodeRedemptionRepository.countByPromoCodeIdAndGuestEmailIgnoreCase(promoCode.getId(), guestEmail)
                        >= promoCode.getMaxRedemptionsPerUser()) {
                    return "Promo code already used by this guest email";
                }
            } else {
                // A per-customer limit can't be enforced without an identity, so an
                // anonymous guest must provide an email to use a limited code.
                return "An email is required to use this promo code";
            }
        }

        return null;
    }

    private BigDecimal calculateDiscountAmount(PromoCode promoCode, BigDecimal bookingAmount) {
        BigDecimal discountAmount;

        if (promoCode.getDiscountType() == PromoDiscountType.PERCENTAGE) {
            discountAmount = bookingAmount
                    .multiply(promoCode.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            discountAmount = promoCode.getDiscountValue().setScale(2, RoundingMode.HALF_UP);
        }

        if (promoCode.getMaxDiscountAmount() != null) {
            discountAmount = discountAmount.min(promoCode.getMaxDiscountAmount());
        }

        return discountAmount.min(bookingAmount).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public AppliedPromoCode applyPromoCodeForBooking(
            UUID userId,
            String promoCodeText,
            String guestEmail,
            BigDecimal bookingAmount,
            String currency,
            UUID experienceId
    ) {
        AppliedPromoCodes applied = applyPromoCodesForBooking(
                userId,
                promoCodeText == null ? List.of() : List.of(promoCodeText),
                guestEmail,
                bookingAmount,
                currency,
                experienceId
        );

        if (applied.codes().isEmpty()) {
            return new AppliedPromoCode(null, applied.totalDiscount(), applied.finalAmount());
        }

        return new AppliedPromoCode(
                applied.codes().get(0).promoCode(),
                applied.totalDiscount(),
                applied.finalAmount()
        );
    }

    /**
     * Validates and applies one or more codes to a booking, stacking combinable codes.
     * Percentage codes apply first (to the larger amount), then fixed-amount codes — each on
     * the running total and clamped at zero. When more than one code is supplied, every code
     * must be {@code combinable}; otherwise only a single code may be used.
     */
    @Transactional(readOnly = true)
    public AppliedPromoCodes applyPromoCodesForBooking(
            UUID userId,
            List<String> promoCodeTexts,
            String guestEmail,
            BigDecimal bookingAmount,
            String currency,
            UUID experienceId
    ) {
        BigDecimal base = bookingAmount.setScale(2, RoundingMode.HALF_UP);

        List<String> normalizedCodes = (promoCodeTexts == null ? List.<String>of() : promoCodeTexts).stream()
                .filter(c -> c != null && !c.trim().isEmpty())
                .map(this::normalizeCode)
                .distinct()
                .toList();

        if (normalizedCodes.isEmpty()) {
            return new AppliedPromoCodes(List.of(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), base);
        }

        String requestCurrency = currency.trim().toUpperCase(Locale.ROOT);
        String trimmedGuestEmail = optionalTrim(guestEmail);

        List<PromoCode> codes = new ArrayList<>();
        for (String code : normalizedCodes) {
            PromoCode promoCode = promoCodeRepository.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> new BadRequestException("Promo code not found: " + code));

            String validationError = getValidationError(promoCode, userId, trimmedGuestEmail, base, requestCurrency, experienceId);
            if (validationError != null) {
                throw new BadRequestException(validationError);
            }
            codes.add(promoCode);
        }

        if (codes.size() > 1 && codes.stream().anyMatch(c -> !c.isCombinable())) {
            throw new BadRequestException("These codes can't be combined; only one can be applied");
        }

        // Percentage codes first (they apply to the larger amount), then fixed-amount codes.
        codes.sort(Comparator.comparingInt(
                c -> c.getDiscountType() == PromoDiscountType.PERCENTAGE ? 0 : 1));

        BigDecimal running = base;
        List<AppliedPromoCode> applied = new ArrayList<>();
        for (PromoCode promoCode : codes) {
            BigDecimal discount = calculateDiscountAmount(promoCode, running);
            running = running.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            applied.add(new AppliedPromoCode(promoCode, discount, running));
        }

        BigDecimal totalDiscount = base.subtract(running).setScale(2, RoundingMode.HALF_UP);
        return new AppliedPromoCodes(applied, totalDiscount, running);
    }

    private void validatePromoConfig(CreatePromoCodeRequest request) {
        if (request.discountType() == PromoDiscountType.PERCENTAGE &&
                request.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("Percentage discount cannot exceed 100");
        }

        // Minimum spend is optional: null or 0 both mean "no minimum". It just can't be negative.
        if (request.minBookingAmount() != null && request.minBookingAmount().signum() < 0) {
            throw new BadRequestException("Minimum booking amount cannot be negative");
        }

        if (request.maxTotalRedemptions() != null && request.maxTotalRedemptions() < 1) {
            throw new BadRequestException("Max total redemptions must be greater than zero");
        }

        if (request.maxRedemptionsPerUser() != null && request.maxRedemptionsPerUser() < 1) {
            throw new BadRequestException("Max redemptions per user must be greater than zero");
        }

        if (request.startsAt() != null && request.expiresAt() != null &&
                !request.expiresAt().isAfter(request.startsAt())) {
            throw new BadRequestException("Expiry time must be after start time");
        }

        if (request.discountBearer() == DiscountBearer.SPLIT) {
            BigDecimal share = request.platformSharePercentage();
            if (share == null
                    || share.compareTo(BigDecimal.ZERO) < 0
                    || share.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BadRequestException("Split discounts require a platform share between 0 and 100");
            }
        }
    }

    @Transactional
    public void redeemPromoCodeForPaidBooking(Booking booking) {
        List<BookingPromoCode> applied = booking.getAppliedPromoCodes();
        if (applied != null && !applied.isEmpty()) {
            for (BookingPromoCode code : applied) {
                recordRedemption(booking, code.getPromoCode(), code.getDiscountAmount());
            }
            return;
        }
        // Fallback for bookings created before multi-code stacking.
        recordRedemption(booking, booking.getPromoCode(), booking.getDiscountAmount());
    }

    private void recordRedemption(Booking booking, PromoCode promoCode, BigDecimal discountAmount) {
        if (promoCode == null) {
            return;
        }
        if (promoCodeRedemptionRepository.existsByBookingIdAndPromoCodeId(booking.getId(), promoCode.getId())) {
            return;
        }

        PromoCodeRedemption redemption = new PromoCodeRedemption();
        redemption.setPromoCode(promoCode);
        redemption.setUser(booking.getLoggedInUser());
        redemption.setBooking(booking);
        redemption.setGuestEmail(booking.getGuestEmail());
        redemption.setDiscountAmount(discountAmount);
        redemption.setRedeemedAt(Instant.now());

        promoCodeRedemptionRepository.save(redemption);

        // Atomic DB-level increment so concurrent confirmations can't lose updates
        // and under-count redemptions against the configured limit.
        promoCodeRepository.incrementRedemptions(promoCode.getId());
    }

    private ValidatePromoCodeResponse invalid(String code, BigDecimal bookingAmount, String message) {
        return new ValidatePromoCodeResponse(
                false,
                null,
                code,
                null,
                null,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                bookingAmount.setScale(2, RoundingMode.HALF_UP),
                message
        );
    }

    private PromoCodeResponse toResponse(PromoCode promoCode) {
        return new PromoCodeResponse(
                promoCode.getId(),
                promoCode.getCode(),
                promoCode.getDescription(),
                promoCode.getDiscountType(),
                promoCode.getDiscountValue(),
                promoCode.getCurrency(),
                promoCode.getMaxDiscountAmount(),
                promoCode.getMinBookingAmount(),
                promoCode.getMaxTotalRedemptions(),
                promoCode.getMaxRedemptionsPerUser(),
                promoCode.getCurrentRedemptions(),
                promoCode.getStartsAt(),
                promoCode.getExpiresAt(),
                promoCode.isActive(),
                promoCode.getCreatedAt(),
                promoCode.getUpdatedAt(),
                promoCode.getExperienceIds() == null ? List.of() : new ArrayList<>(promoCode.getExperienceIds())
        );
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BadRequestException("Promo code is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String optionalUpper(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}