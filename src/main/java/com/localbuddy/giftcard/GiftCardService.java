package com.localbuddy.giftcard;

import com.localbuddy.booking.Booking;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.payment.PaymentCheckoutProvider;
import com.localbuddy.payment.PaymentCheckoutResult;
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
    private final PaymentCheckoutProvider paymentCheckoutProvider;
    private final int validityDays;

    public GiftCardService(GiftCardRepository giftCardRepository,
                           GiftCardRedemptionRepository redemptionRepository,
                           GiftCardBookingRepository bookingRepository,
                           UserRepository userRepository,
                           PaymentCheckoutProvider paymentCheckoutProvider,
                           @Value("${app.giftcard.validity-days:365}") int validityDays) {
        this.giftCardRepository = giftCardRepository;
        this.redemptionRepository = redemptionRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.paymentCheckoutProvider = paymentCheckoutProvider;
        this.validityDays = validityDays;
    }

    @Transactional
    public GiftCardPurchaseResponse purchase(UUID purchaserUserId, PurchaseGiftCardRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BadRequestException("Gift card amount must be positive");
        }

        GiftCard card = new GiftCard();
        card.setCode(generateUniqueCode());
        card.setInitialAmount(amount);
        card.setBalance(amount);
        card.setCurrency(normalizeCurrency(request.currency()));
        // Not spendable until the purchase payment succeeds (activated by the Stripe webhook).
        card.setStatus(GiftCardStatus.PENDING_PAYMENT);
        card.setRecipientEmail(trimToNull(request.recipientEmail()));
        card.setRecipientName(trimToNull(request.recipientName()));
        card.setMessage(trimToNull(request.message()));
        // Purchased gift cards are stored value and never expire (it is the customer's money).
        card.setExpiresAt(null);

        if (purchaserUserId != null) {
            User purchaser = userRepository.findById(purchaserUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            card.setPurchaserUser(purchaser);
            card.setPurchaserEmail(purchaser.getEmail());
        }

        GiftCard saved = giftCardRepository.save(card);

        PaymentCheckoutResult checkout = paymentCheckoutProvider.createGiftCardCheckout(
                saved.getId(), amount, saved.getCurrency(), saved.getCode());

        return new GiftCardPurchaseResponse(
                saved.getId(), saved.getCode(), saved.getInitialAmount(),
                saved.getCurrency(), saved.getStatus(), checkout.checkoutUrl());
    }

    /** Activates a purchased gift card once its Stripe payment completes (idempotent). */
    @Transactional
    public void activatePurchasedCard(UUID giftCardId) {
        GiftCard card = giftCardRepository.findByIdForUpdate(giftCardId)
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
        if (card.getStatus() == GiftCardStatus.PENDING_PAYMENT) {
            card.setStatus(GiftCardStatus.ACTIVE);
            giftCardRepository.save(card);
        }
    }

    @Transactional
    public GiftCardBalanceResponse checkBalance(String code) {
        GiftCard card = requireCard(code);
        expireIfNeeded(card);
        return GiftCardBalanceResponse.from(card);
    }

    @Transactional
    public GiftCardBalanceResponse redeem(UUID userId, RedeemGiftCardRequest request) {
        // Resolve the code, then re-load under a row lock so concurrent redemptions
        // of the same card serialise and cannot double-spend the balance.
        UUID cardId = requireCard(request.code()).getId();
        GiftCard card = giftCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
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

    /**
     * Reserves gift-card balance for a booking checkout: locks the card, validates it, and
     * decrements by min(balance, amountDue). Returns which card and how much it covered, so the
     * remainder can be charged to Stripe. Released via {@link #returnToCard} on failure/refund.
     */
    @Transactional
    public GiftCardApplication reserveForCheckout(String code, BigDecimal amountDue) {
        UUID cardId = requireCard(code).getId();
        GiftCard card = giftCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
        expireIfNeeded(card);

        if (card.getStatus() != GiftCardStatus.ACTIVE) {
            throw new BadRequestException("Gift card is not active (status: " + card.getStatus() + ")");
        }

        BigDecimal due = amountDue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal applied = card.getBalance().min(due).setScale(2, RoundingMode.HALF_UP);
        if (applied.signum() <= 0) {
            throw new BadRequestException("Gift card has no balance to apply");
        }

        card.setBalance(card.getBalance().subtract(applied));
        if (card.getBalance().signum() == 0) {
            card.setStatus(GiftCardStatus.DEPLETED);
        }
        giftCardRepository.save(card);

        return new GiftCardApplication(card.getId(), applied);
    }

    /** Returns funds to a card — releasing an unused reservation or refunding a cancelled booking. */
    @Transactional
    public void returnToCard(UUID cardId, BigDecimal amount) {
        if (cardId == null || amount == null || amount.signum() <= 0) {
            return;
        }
        GiftCard card = giftCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));
        card.setBalance(card.getBalance().add(amount.setScale(2, RoundingMode.HALF_UP)));
        if (card.getStatus() == GiftCardStatus.DEPLETED && card.getBalance().signum() > 0) {
            card.setStatus(GiftCardStatus.ACTIVE);
        }
        giftCardRepository.save(card);
    }

    /** Records a confirmed gift-card redemption against a booking (after the payment succeeds). */
    @Transactional
    public void recordBookingRedemption(UUID cardId, UUID bookingId, UUID userId, BigDecimal amount) {
        if (cardId == null || amount == null || amount.signum() <= 0) {
            return;
        }
        GiftCardRedemption redemption = new GiftCardRedemption();
        giftCardRepository.findById(cardId).ifPresent(redemption::setGiftCard);
        redemption.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        if (userId != null) {
            userRepository.findById(userId).ifPresent(redemption::setRedeemedByUser);
        }
        if (bookingId != null) {
            bookingRepository.findById(bookingId).ifPresent(redemption::setBooking);
        }
        redemptionRepository.save(redemption);
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
