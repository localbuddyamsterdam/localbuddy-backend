package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingSource;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.giftcard.GiftCardApplication;
import com.localbuddy.giftcard.GiftCardService;
import com.localbuddy.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentTransactionService {

    /** Hard cap on bookings per bundle checkout (matches a multi-day trip plan comfortably). */
    static final int MAX_GROUP_SIZE = 12;

    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final com.localbuddy.pricing.PricingEngine pricingEngine;
    private final GiftCardService giftCardService;
    private final PaymentGroupRepository paymentGroupRepository;

    public PaymentTransactionService(
            PaymentRepository paymentRepository,
            BookingRepository bookingRepository,
            com.localbuddy.pricing.PricingEngine pricingEngine,
            GiftCardService giftCardService,
            PaymentGroupRepository paymentGroupRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.pricingEngine = pricingEngine;
        this.giftCardService = giftCardService;
        this.paymentGroupRepository = paymentGroupRepository;
    }

    @Transactional
    public Payment preparePaymentForCheckout(UUID userId, UUID bookingId, String giftCardCode) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validatePaymentRequestUser(userId, booking);
        validateBookingReadyForCheckout(booking);

        Payment payment = getOrCreatePendingPaymentForBooking(booking);

        if (payment.getPaymentGroup() != null) {
            throw new BadRequestException(
                    "This booking is part of a bundle checkout — pay via the bundle link, or wait for it to expire");
        }

        // Apply a gift card atomically with the payment (same transaction) before checkout.
        if (giftCardCode != null && !giftCardCode.trim().isEmpty()
                && payment.getGiftCardId() == null
                && payment.getPaymentStatus() == PaymentStatus.PENDING) {
            GiftCardApplication application = giftCardService.reserveForCheckout(giftCardCode, payment.getAmount());
            payment.setGiftCardId(application.giftCardId());
            payment.setGiftCardAmount(application.amount());
            payment = paymentRepository.save(payment);
        }

        // Initialize lazy values needed later outside the transaction
        payment.getId();
        payment.getAmount();
        payment.getCurrency();

        Booking paymentBooking = payment.getBooking();
        paymentBooking.getId();
        paymentBooking.getBookingReference();

        return payment;
    }

    @Transactional
    public Payment attachCheckoutResult(UUID paymentId, PaymentCheckoutResult checkoutResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BadRequestException("Payment is already completed for this booking");
        }

        payment.setPaymentStatus(PaymentStatus.PROCESSING);
        payment.setCheckoutUrl(checkoutResult.checkoutUrl());
        payment.setProviderCheckoutSessionId(checkoutResult.providerCheckoutSessionId());
        payment.setProviderPaymentIntentId(checkoutResult.providerPaymentIntentId());

        if (checkoutResult.paymentMethodType() != null) {
            payment.setPaymentMethodType(checkoutResult.paymentMethodType());
        }

        return paymentRepository.save(payment);
    }

    /**
     * Transactional half of a bundle checkout: validates ownership + readiness of every booking,
     * creates (or reuses) one member payment per booking with its full per-booking financial
     * snapshot, creates the group, reserves an optional gift card ONCE against the group total and
     * distributes the draw across members in order. The Stripe call happens outside, in
     * {@link PaymentService#createGroupCheckout}.
     *
     * @param loggedInUserId owner for logged-in bundles (null for guest bundles)
     * @param guestEmail     owner email for guest bundles (null for logged-in bundles)
     */
    @Transactional
    public GroupCheckoutPreparation prepareGroupForCheckout(
            UUID loggedInUserId,
            String guestEmail,
            List<UUID> bookingIds,
            String giftCardCode,
            UUID tripPlanId
    ) {
        if (bookingIds == null || bookingIds.isEmpty()) {
            throw new BadRequestException("At least one booking is required for a bundle checkout");
        }
        if ((loggedInUserId == null) == (guestEmail == null || guestEmail.isBlank())) {
            throw new BadRequestException("A bundle checkout needs exactly one owner (user or guest email)");
        }

        List<UUID> distinctBookingIds = bookingIds.stream().distinct().toList();
        if (distinctBookingIds.size() > MAX_GROUP_SIZE) {
            throw new BadRequestException("A bundle checkout can include at most " + MAX_GROUP_SIZE + " bookings");
        }

        String normalizedGuestEmail = guestEmail != null
                ? guestEmail.trim().toLowerCase(Locale.ROOT) : null;

        List<Payment> members = new ArrayList<>();
        User owner = null;
        String currency = null;
        BigDecimal total = BigDecimal.ZERO;

        for (UUID bookingId : distinctBookingIds) {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

            if (loggedInUserId != null) {
                validatePaymentRequestUser(loggedInUserId, booking);
                owner = booking.getLoggedInUser();
            } else {
                if (booking.getBookingSource() != BookingSource.GUEST_USER
                        || booking.getGuestEmail() == null
                        || !booking.getGuestEmail().equalsIgnoreCase(normalizedGuestEmail)) {
                    throw new ResourceNotFoundException("Booking not found");
                }
            }

            validateBookingReadyForCheckout(booking);

            Payment payment = getOrCreatePendingPaymentForBooking(booking);

            if (payment.getPaymentGroup() != null) {
                throw new BadRequestException("Booking " + booking.getBookingReference()
                        + " is already part of a bundle checkout");
            }
            if (payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
                throw new BadRequestException("Booking " + booking.getBookingReference()
                        + " already has a checkout in progress — finish or let it expire first");
            }
            if (payment.getGiftCardId() != null) {
                // Mixing a member-level gift card with the group-level draw would double-count;
                // such a booking must be paid individually.
                throw new BadRequestException("Booking " + booking.getBookingReference()
                        + " already has a gift card applied — pay it individually");
            }

            if (currency == null) {
                currency = payment.getCurrency();
            } else if (!currency.equalsIgnoreCase(payment.getCurrency())) {
                throw new BadRequestException(
                        "All bookings in a bundle checkout must use the same currency");
            }

            total = total.add(payment.getAmount());
            members.add(payment);
        }

        PaymentGroup group = new PaymentGroup();
        group.setGroupToken(generateGroupToken());
        group.setTripPlanId(tripPlanId);
        group.setLoggedInUser(owner);
        group.setGuestEmail(normalizedGuestEmail);
        group.setStatus(PaymentGroupStatus.PENDING);
        group.setProvider(PaymentProvider.STRIPE);
        group.setTotalAmount(total);
        group.setCurrency(currency != null ? currency.toUpperCase(Locale.ROOT) : "EUR");
        group = paymentGroupRepository.save(group);

        if (giftCardCode != null && !giftCardCode.trim().isEmpty()) {
            GiftCardApplication application = giftCardService.reserveForCheckout(giftCardCode, total);
            group.setGiftCardId(application.giftCardId());
            group.setGiftCardAmount(application.amount());
            group = paymentGroupRepository.save(group);

            // Distribute the single draw across members in order, so per-member refund math
            // (gift share back to card, cash share via Stripe) stays exact.
            BigDecimal remaining = application.amount();
            for (Payment member : members) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal share = remaining.min(member.getAmount());
                member.setGiftCardId(application.giftCardId());
                member.setGiftCardAmount(share);
                remaining = remaining.subtract(share);
            }
        }

        for (Payment member : members) {
            member.setPaymentGroup(group);
        }
        paymentRepository.saveAll(members);

        // Initialize lazy values needed outside the transaction (Stripe line items).
        for (Payment member : members) {
            member.getBooking().getBookingReference();
        }

        return new GroupCheckoutPreparation(group, List.copyOf(members));
    }

    private String generateGroupToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(TOKEN_RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    private Payment getOrCreatePendingPaymentForBooking(Booking booking) {
        paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.PAID)
        ).ifPresent(existingPayment -> {
            if (existingPayment.getPaymentStatus() == PaymentStatus.PAID) {
                throw new BadRequestException("Payment is already completed for this booking");
            }
        });

        return paymentRepository.findFirstByBookingIdAndPaymentStatusInOrderByCreatedAtDesc(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING)
        ).orElseGet(() -> createPaymentEntityForBooking(booking));
    }

    private Payment createPaymentEntityForBooking(Booking booking) {
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        pricingEngine.applyTo(payment, booking);

        return paymentRepository.save(payment);
    }

    private void validatePaymentRequestUser(UUID userId, Booking booking) {
        if (booking.getLoggedInUser() != null &&
                booking.getLoggedInUser().getId().equals(userId)) {
            return;
        }

        throw new ResourceNotFoundException("Booking not found");
    }

    private void validateBookingReadyForCheckout(Booking booking) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.ACCEPTED) {
            throw new BadRequestException("Checkout can be created only for bookings pending payment");
        }
    }
}