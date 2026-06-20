package com.localbuddy.giftcard;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class GiftCardService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GiftCardRepository giftCardRepository;
    private final GiftCardRedemptionRepository redemptionRepository;
    private final GiftCardBookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final int validityDays;

    public GiftCardService(GiftCardRepository giftCardRepository,
                           GiftCardRedemptionRepository redemptionRepository,
                           GiftCardBookingRepository bookingRepository,
                           UserRepository userRepository,
                           @Value("${app.giftcard.validity-days:365}") int validityDays) {
        this.giftCardRepository = giftCardRepository;
        this.redemptionRepository = redemptionRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.validityDays = validityDays;
    }

    @Transactional
    public GiftCardResponse purchase(UUID purchaserUserId, PurchaseGiftCardRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BadRequestException("Gift card amount must be positive");
        }

        GiftCard card = new GiftCard();
        card.setCode(generateUniqueCode());
        card.setInitialAmount(amount);
        card.setBalance(amount);
        card.setCurrency(normalizeCurrency(request.currency()));
        card.setStatus(GiftCardStatus.ACTIVE);
        card.setRecipientEmail(trimToNull(request.recipientEmail()));
        card.setRecipientName(trimToNull(request.recipientName()));
        card.setMessage(trimToNull(request.message()));
        card.setExpiresAt(Instant.now().plus(validityDays, ChronoUnit.DAYS));

        if (purchaserUserId != null) {
            User purchaser = userRepository.findById(purchaserUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            card.setPurchaserUser(purchaser);
            card.setPurchaserEmail(purchaser.getEmail());
        }

        return GiftCardResponse.from(giftCardRepository.save(card));
    }

    @Transactional
    public GiftCardBalanceResponse checkBalance(String code) {
        GiftCard card = requireCard(code);
        expireIfNeeded(card);
        return GiftCardBalanceResponse.from(card);
    }

    @Transactional
    public GiftCardBalanceResponse redeem(UUID userId, RedeemGiftCardRequest request) {
        GiftCard card = requireCard(request.code());
        expireIfNeeded(card);

        if (card.getStatus() != GiftCardStatus.ACTIVE) {
            throw new BadRequestException("Gift card is not active (status: " + card.getStatus() + ")");
        }

        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BadRequestException("Redemption amount must be positive");
        }
        if (amount.compareTo(card.getBalance()) > 0) {
            throw new BadRequestException("Redemption amount exceeds the remaining balance");
        }

        card.setBalance(card.getBalance().subtract(amount));
        if (card.getBalance().signum() == 0) {
            card.setStatus(GiftCardStatus.DEPLETED);
        }
        giftCardRepository.save(card);

        GiftCardRedemption redemption = new GiftCardRedemption();
        redemption.setGiftCard(card);
        redemption.setAmount(amount);
        if (userId != null) {
            userRepository.findById(userId).ifPresent(redemption::setRedeemedByUser);
        }
        if (request.bookingId() != null) {
            Booking booking = bookingRepository.findById(request.bookingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
            redemption.setBooking(booking);
        }
        redemptionRepository.save(redemption);

        return GiftCardBalanceResponse.from(card);
    }

    @Transactional(readOnly = true)
    public List<GiftCardResponse> getMyGiftCards(UUID userId) {
        return giftCardRepository.findByPurchaserUserIdOrderByCreatedAtDesc(userId)
                .stream().map(GiftCardResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GiftCardResponse> listAll() {
        return giftCardRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(GiftCardResponse::from).toList();
    }

    @Transactional
    public GiftCardResponse cancel(UUID giftCardId) {
        GiftCard card = giftCardRepository.findById(giftCardId)
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
        card.setStatus(GiftCardStatus.CANCELLED);
        return GiftCardResponse.from(giftCardRepository.save(card));
    }

    private void expireIfNeeded(GiftCard card) {
        if (card.getStatus() == GiftCardStatus.ACTIVE
                && card.getExpiresAt() != null
                && card.getExpiresAt().isBefore(Instant.now())) {
            card.setStatus(GiftCardStatus.EXPIRED);
            giftCardRepository.save(card);
        }
    }

    private GiftCard requireCard(String code) {
        return giftCardRepository.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "LB-" + randomGroup() + "-" + randomGroup() + "-" + randomGroup();
            if (!giftCardRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new BadRequestException("Could not generate a unique gift card code, please retry");
    }

    private String randomGroup() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return "EUR";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
