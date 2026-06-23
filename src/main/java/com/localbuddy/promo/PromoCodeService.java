package com.localbuddy.promo;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.localbuddy.booking.Booking;
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

        String validationError = getValidationError(promoCode, userId, optionalTrim(request.guestEmail()), bookingAmount, requestCurrency);

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
                                      String currency) {
        Instant now = Instant.now();

        if (!promoCode.isActive()) {
            return "Promo code is inactive";
        }

        if (promoCode.getStartsAt() != null && now.isBefore(promoCode.getStartsAt())) {
            return "Promo code is not active yet";
        }

        if (promoCode.getExpiresAt() != null && now.isAfter(promoCode.getExpiresAt())) {
            return "Promo code has expired";
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

        if (promoCode.getMaxRedemptionsPerUser() != null && userId != null &&
                promoCodeRedemptionRepository.countByPromoCodeIdAndUserId(promoCode.getId(), userId) >= promoCode.getMaxRedemptionsPerUser()) {
            return "Promo code already used by this user";
        }

        if (promoCode.getMaxRedemptionsPerUser() != null && userId == null && guestEmail != null &&
                promoCodeRedemptionRepository.countByPromoCodeIdAndGuestEmailIgnoreCase(promoCode.getId(), guestEmail) >= promoCode.getMaxRedemptionsPerUser()) {
            return "Promo code already used by this guest email";
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
            String currency
    ) {
        if (promoCodeText == null || promoCodeText.trim().isEmpty()) {
            return new AppliedPromoCode(
                    null,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    bookingAmount.setScale(2, RoundingMode.HALF_UP)
            );
        }

        String normalizedCode = normalizeCode(promoCodeText);
        String requestCurrency = currency.trim().toUpperCase(Locale.ROOT);
        BigDecimal normalizedBookingAmount = bookingAmount.setScale(2, RoundingMode.HALF_UP);

        PromoCode promoCode = promoCodeRepository.findByCodeIgnoreCase(normalizedCode)
                .orElseThrow(() -> new BadRequestException("Promo code not found"));

        String validationError = getValidationError(
                promoCode,
                userId,
                optionalTrim(guestEmail),
                normalizedBookingAmount,
                requestCurrency
        );

        if (validationError != null) {
            throw new BadRequestException(validationError);
        }

        BigDecimal discountAmount = calculateDiscountAmount(promoCode, normalizedBookingAmount);
        BigDecimal finalAmount = normalizedBookingAmount
                .subtract(discountAmount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return new AppliedPromoCode(
                promoCode,
                discountAmount,
                finalAmount
        );
    }

    private void validatePromoConfig(CreatePromoCodeRequest request) {
        if (request.discountType() == PromoDiscountType.PERCENTAGE &&
                request.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("Percentage discount cannot exceed 100");
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
    }

    @Transactional
    public void redeemPromoCodeForPaidBooking(Booking booking) {
        if (booking.getPromoCode() == null) {
            return;
        }

        boolean alreadyRedeemed = promoCodeRedemptionRepository
                .existsByBookingId(booking.getId());

        if (alreadyRedeemed) {
            return;
        }

        PromoCodeRedemption redemption = new PromoCodeRedemption();
        redemption.setPromoCode(booking.getPromoCode());
        redemption.setUser(booking.getLoggedInUser());
        redemption.setBooking(booking);
        redemption.setGuestEmail(booking.getGuestEmail());
        redemption.setDiscountAmount(booking.getDiscountAmount());
        redemption.setRedeemedAt(Instant.now());

        promoCodeRedemptionRepository.save(redemption);

        PromoCode promoCode = booking.getPromoCode();
        promoCode.setCurrentRedemptions(promoCode.getCurrentRedemptions() + 1);
        promoCodeRepository.save(promoCode);
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
                promoCode.getUpdatedAt()
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