package com.localbuddy.payment;

import com.localbuddy.booking.*;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.giftcard.GiftCardApplication;
import com.localbuddy.giftcard.GiftCardService;
import com.localbuddy.payout.HostLedgerService;
import com.localbuddy.pricing.PricingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final BigDecimal commissionPercentage;
    private final PaymentCheckoutProvider paymentCheckoutProvider;
    private final PaymentWebhookEventRepository paymentWebhookEventRepository;
    private final CancellationRefundPolicyService cancellationRefundPolicyService;
    private final PaymentTransactionService paymentTransactionService;
    private final BookingExpiryService bookingExpiryService;
    private final PricingEngine pricingEngine;
    private final HostLedgerService hostLedgerService;
    private final GiftCardService giftCardService;
    private final PaidBookingFinalizer paidBookingFinalizer;
    private final BigDecimal stripeMinimumCharge;
    private final PaymentGroupRepository paymentGroupRepository;

    // Self-reference (lazy, to break the construction cycle). createCheckout is deliberately NOT
    // @Transactional — it must not hold a DB transaction open across the Stripe network call — so
    // invoking its @Transactional siblings on `this` would bypass their @Transactional (Spring
    // self-invocation). Routing through `self` runs them in a real transaction, so the lazy
    // booking proxy used by finalizePaidBooking can initialise.
    @Autowired
    @Lazy
    private PaymentService self;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          @Value("${app.platform.commission-percentage:20}") BigDecimal commissionPercentage, PaymentCheckoutProvider paymentCheckoutProvider, PaymentWebhookEventRepository paymentWebhookEventRepository, CancellationRefundPolicyService cancellationRefundPolicyService, PaymentTransactionService paymentTransactionService, BookingExpiryService bookingExpiryService, PricingEngine pricingEngine, HostLedgerService hostLedgerService, GiftCardService giftCardService, PaidBookingFinalizer paidBookingFinalizer, @Value("${app.payments.stripe.minimum-charge:0.50}") BigDecimal stripeMinimumCharge, PaymentGroupRepository paymentGroupRepository) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.commissionPercentage = commissionPercentage;
        this.paymentCheckoutProvider = paymentCheckoutProvider;
        this.paymentWebhookEventRepository = paymentWebhookEventRepository;
        this.cancellationRefundPolicyService = cancellationRefundPolicyService;
        this.paymentTransactionService = paymentTransactionService;
        this.bookingExpiryService = bookingExpiryService;
        this.pricingEngine = pricingEngine;
        this.hostLedgerService = hostLedgerService;
        this.giftCardService = giftCardService;
        this.paidBookingFinalizer = paidBookingFinalizer;
        this.stripeMinimumCharge = stripeMinimumCharge;
        this.paymentGroupRepository = paymentGroupRepository;
    }

    @Transactional
    public PaymentResponse createPendingPayment(UUID userId, CreatePaymentRequest request) {
        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validatePaymentRequestUser(userId, booking);

        validateBookingReadyForCheckout(booking);


        if (paymentRepository.existsByBookingIdAndPaymentStatusIn(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.PAID)
        )) {
            throw new BadRequestException("Active payment already exists for this booking");
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        pricingEngine.applyTo(payment, booking);

        return toResponse(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        validatePaymentRequestUser(userId, payment.getBooking());

        return toResponse(payment);
    }

    private void validatePaymentRequestUser(UUID userId, Booking booking) {
        if (booking.getLoggedInUser() != null &&
                booking.getLoggedInUser().getId().equals(userId)) {
            return;
        }

        throw new ResourceNotFoundException("Booking not found");
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBooking().getId(),
                payment.getProvider(),
                payment.getPaymentMethodType(),
                payment.getPaymentStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPlatformFeeAmount(),
                payment.getLocalPayoutAmount(),
                payment.getProviderCheckoutSessionId(),
                payment.getProviderPaymentIntentId(),
                payment.getCheckoutUrl(),
                payment.getPaidAt(),
                payment.getFailedAt(),
                payment.getCancelledAt(),
                payment.getRefundedAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getRefundedAmount(),
                payment.getRefundReason()
        );
    }

    @Transactional
    public PaymentResponse createPendingGuestPayment(CreateGuestPaymentRequest request) {
        String normalizedReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String normalizedEmail = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));

        if (booking.getBookingSource() != BookingSource.GUEST_USER) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        validateBookingReadyForCheckout(booking);


        if (paymentRepository.existsByBookingIdAndPaymentStatusIn(
                booking.getId(),
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.PAID)
        )) {
            throw new BadRequestException("Active payment already exists for this booking");
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        pricingEngine.applyTo(payment, booking);

        return toResponse(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse lookupGuestPayment(GuestPaymentLookupRequest request) {
        String normalizedReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String normalizedEmail = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest payment not found"));

        if (booking.getBookingSource() != BookingSource.GUEST_USER) {
            throw new ResourceNotFoundException("Guest payment not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest payment not found");
        }

        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Guest payment not found"));

        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getAdminPayments(PaymentStatus status) {
        if (status != null) {
            return paymentRepository.findByPaymentStatusOrderByCreatedAtDesc(status)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return paymentRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public PaymentCheckoutResponse createCheckout(UUID userId, CreatePaymentRequest request) {
        Payment payment = paymentTransactionService.preparePaymentForCheckout(
                userId,
                request.bookingId(),
                request.giftCardCode()
        );

        if (payment.getPaymentStatus() == PaymentStatus.PROCESSING &&
                payment.getCheckoutUrl() != null &&
                !payment.getCheckoutUrl().trim().isEmpty()) {
            return toCheckoutResponse(payment);
        }

        if (shouldCompleteWithoutStripeCharge(payment)) {
            return self.completeWithoutStripeCharge(payment.getId());
        }

        try {
            PaymentCheckoutResult checkoutResult = paymentCheckoutProvider.createCheckout(payment);

            Payment savedPayment = paymentTransactionService.attachCheckoutResult(
                    payment.getId(),
                    checkoutResult
            );

            return toCheckoutResponse(savedPayment);
        } catch (RuntimeException ex) {
            self.releaseGiftCardOnFailure(payment.getId());
            throw ex;
        }
    }



    @Transactional
    public PaymentCheckoutResponse createGuestCheckout(CreateGuestPaymentRequest request) {
        String normalizedReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String normalizedEmail = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));

        if (booking.getBookingSource() != BookingSource.GUEST_USER) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        validateBookingReadyForCheckout(booking);

        Payment payment = getOrCreatePendingPaymentForBooking(booking);

        if (payment.getPaymentGroup() != null) {
            throw new BadRequestException(
                    "This booking is part of a bundle checkout — pay via the bundle link, or wait for it to expire");
        }

        if (payment.getPaymentStatus() == PaymentStatus.PROCESSING &&
                payment.getCheckoutUrl() != null &&
                !payment.getCheckoutUrl().trim().isEmpty()) {
            return toCheckoutResponse(payment);
        }

        if (request.giftCardCode() != null && !request.giftCardCode().trim().isEmpty()
                && payment.getGiftCardId() == null && payment.getPaymentStatus() == PaymentStatus.PENDING) {
            GiftCardApplication application =
                    giftCardService.reserveForCheckout(request.giftCardCode(), payment.getAmount());
            payment.setGiftCardId(application.giftCardId());
            payment.setGiftCardAmount(application.amount());
            payment = paymentRepository.save(payment);
        }

        if (shouldCompleteWithoutStripeCharge(payment)) {
            payment.setPaymentStatus(PaymentStatus.PAID);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
            finalizePaidBooking(payment, booking);
            return toCheckoutResponse(payment);
        }

        PaymentCheckoutResult checkoutResult = paymentCheckoutProvider.createCheckout(payment);

        payment.setPaymentStatus(PaymentStatus.PROCESSING);
        payment.setCheckoutUrl(checkoutResult.checkoutUrl());
        payment.setProviderCheckoutSessionId(checkoutResult.providerCheckoutSessionId());
        payment.setProviderPaymentIntentId(checkoutResult.providerPaymentIntentId());

        if (checkoutResult.paymentMethodType() != null) {
            payment.setPaymentMethodType(checkoutResult.paymentMethodType());
        }

        Payment savedPayment = paymentRepository.save(payment);

        return toCheckoutResponse(savedPayment);
    }


    /**
     * True when the booking needs no Stripe charge: a gift card covers the full amount, or it
     * covers all but a remainder below Stripe's minimum charge. Stripe rejects charges under its
     * per-currency floor (≈€0.50), so rather than block an otherwise-paid booking, the platform
     * absorbs that sub-minimum remainder. Only applies when a gift card was actually reserved.
     */
    private boolean shouldCompleteWithoutStripeCharge(Payment payment) {
        BigDecimal gift = payment.getGiftCardAmount() != null ? payment.getGiftCardAmount() : BigDecimal.ZERO;
        if (gift.signum() <= 0) {
            return false;
        }
        BigDecimal cashRemainder = payment.getAmount().subtract(gift);
        return cashRemainder.compareTo(stripeMinimumCharge) < 0;
    }

    /**
     * Completes a booking with no Stripe charge — the gift card covers the whole amount, or all but
     * a sub-minimum remainder the platform absorbs (see {@link #shouldCompleteWithoutStripeCharge}).
     */
    @Transactional
    public PaymentCheckoutResponse completeWithoutStripeCharge(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        Booking booking = payment.getBooking();

        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setPaidAt(Instant.now());
        paymentRepository.save(payment);

        finalizePaidBooking(payment, booking);

        return toCheckoutResponse(payment);
    }

    /**
     * Shared post-payment finalisation. The customer's money is already captured before this runs, so
     * nothing here may fail the request or undo the payment.
     *
     * <p>Only confirming the booking is critical, and it happens in the caller's transaction —
     * atomically with the PAID payment — via {@link #confirmBookingAfterPayment}. Everything else
     * (promo/referral redemption, host-ledger earning, gift-card redemption, invoices, and the
     * confirmation notification) is best-effort and reconcilable, so it runs after that transaction
     * commits, each step isolated in its own transaction. See {@link #runBestEffortFinalization} and
     * {@link PaidBookingFinalizer} for why isolation (not just a try/catch) is required.
     */
    private void finalizePaidBooking(Payment payment, Booking booking) {
        confirmBookingAfterPayment(booking);
        runBestEffortFinalization(payment.getId(), booking.getId());
    }

    /**
     * Schedules the reconcilable post-payment side-effects to run once the confirming transaction
     * commits. Each step runs in its own {@code REQUIRES_NEW} transaction inside {@link #safely}, so a
     * failure — or the rollback Spring forces when a participating {@code @Transactional} downstream
     * call throws — is contained to that step and can never turn the already-paid, already-confirmed
     * booking into a rollback or an HTTP 500. Failures are logged with the id needed to reconcile.
     */
    private void runBestEffortFinalization(UUID paymentId, UUID bookingId) {
        afterCommit(() -> {
            safely("promo-code redemption", bookingId, () -> paidBookingFinalizer.redeemPromoCode(bookingId));
            safely("referral redemption", bookingId, () -> paidBookingFinalizer.redeemReferralCode(bookingId));
            safely("host-ledger earning", paymentId, () -> paidBookingFinalizer.recordHostEarning(paymentId));
            safely("gift-card redemption", paymentId, () -> paidBookingFinalizer.recordGiftCardRedemption(paymentId));
            safely("invoice generation", paymentId, () -> paidBookingFinalizer.generateInvoice(paymentId));
        });
    }

    /**
     * Runs {@code action} after the current transaction commits, so post-payment side-effects only
     * fire once the PAID payment and CONFIRMED booking are durable. If no transaction is active
     * (defensive — every caller is transactional today), runs it immediately.
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Runs a single best-effort finalisation step, swallowing and logging any failure so a captured,
     * confirmed booking is never rolled back or surfaced to the customer as a 500. {@code contextId}
     * is the booking/payment id carried into the log so the missed step can be reconciled later.
     */
    private void safely(String step, UUID contextId, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.error("Best-effort post-payment step '{}' failed for id={}; the payment stays captured "
                    + "and the booking confirmed, so this step needs manual reconciliation.", step, contextId, ex);
        }
    }

    private void releaseGiftCardIfAny(Payment payment) {
        if (payment.getGiftCardId() != null
                && payment.getGiftCardAmount() != null
                && payment.getGiftCardAmount().signum() > 0) {
            giftCardService.returnToCard(payment.getGiftCardId(), payment.getGiftCardAmount());
            payment.setGiftCardId(null);
            payment.setGiftCardAmount(BigDecimal.ZERO);
        }
    }

    @Transactional
    public void releaseGiftCardOnFailure(UUID paymentId) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getPaymentStatus() == PaymentStatus.PENDING
                    || payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
                releaseGiftCardIfAny(payment);
                paymentRepository.save(payment);
            }
        });
    }

    private PaymentCheckoutResponse toCheckoutResponse(Payment payment) {
        return new PaymentCheckoutResponse(
                payment.getId(),
                payment.getBooking().getId(),
                payment.getProvider(),
                payment.getPaymentMethodType(),
                payment.getPaymentStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCheckoutUrl()
        );
    }

    @Transactional
    public StripeWebhookResponse handleStripeWebhook(StripeWebhookRequest request) {
        String providerEventId = request.providerEventId().trim();
        String eventType = request.eventType().trim();

        if (paymentWebhookEventRepository.existsByProviderAndProviderEventId(
                PaymentProvider.STRIPE,
                providerEventId
        )) {
            PaymentWebhookEvent existingEvent = paymentWebhookEventRepository
                    .findByProviderAndProviderEventId(PaymentProvider.STRIPE, providerEventId)
                    .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found"));

            return new StripeWebhookResponse(
                    existingEvent.getId(),
                    existingEvent.getProvider(),
                    existingEvent.getProviderEventId(),
                    existingEvent.getEventType(),
                    existingEvent.isProcessed(),
                    "Duplicate webhook ignored"
            );
        }

        PaymentWebhookEvent event = new PaymentWebhookEvent();
        event.setProvider(PaymentProvider.STRIPE);
        event.setProviderEventId(providerEventId);
        event.setEventType(eventType);
        event.setRawPayload(request.rawPayload());

        try {
            if ("checkout.session.completed".equals(eventType)) {
                markPaymentPaidFromCheckoutSession(request);
            }

            event.setProcessed(true);
            event.setProcessedAt(java.time.Instant.now());

        } catch (Exception ex) {
            event.setProcessed(false);
            event.setProcessingError(ex.getMessage());
        }

        PaymentWebhookEvent savedEvent = paymentWebhookEventRepository.save(event);

        return new StripeWebhookResponse(
                savedEvent.getId(),
                savedEvent.getProvider(),
                savedEvent.getProviderEventId(),
                savedEvent.getEventType(),
                savedEvent.isProcessed(),
                savedEvent.isProcessed()
                        ? "Webhook processed successfully"
                        : "Webhook stored but processing failed"
        );
    }

    @Transactional
    public StripeWebhookResponse handleStripeWebhookEvent(Event event, String rawPayload) {
        String providerEventId = event.getId();
        String eventType = event.getType();

        if (paymentWebhookEventRepository.existsByProviderAndProviderEventId(
                PaymentProvider.STRIPE,
                providerEventId
        )) {
            PaymentWebhookEvent existingEvent = paymentWebhookEventRepository
                    .findByProviderAndProviderEventId(PaymentProvider.STRIPE, providerEventId)
                    .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found"));

            return new StripeWebhookResponse(
                    existingEvent.getId(),
                    existingEvent.getProvider(),
                    existingEvent.getProviderEventId(),
                    existingEvent.getEventType(),
                    existingEvent.isProcessed(),
                    "Duplicate webhook ignored"
            );
        }

        PaymentWebhookEvent webhookEvent = new PaymentWebhookEvent();
        webhookEvent.setProvider(PaymentProvider.STRIPE);
        webhookEvent.setProviderEventId(providerEventId);
        webhookEvent.setEventType(eventType);
        webhookEvent.setRawPayload(rawPayload);

        try {
            if ("checkout.session.completed".equals(eventType)) {
                Session session = extractCheckoutSession(event);
                String purchasedGiftCardId = session.getMetadata() != null
                        ? session.getMetadata().get("giftCardId") : null;
                if (purchasedGiftCardId != null && !purchasedGiftCardId.isBlank()) {
                    giftCardService.activatePurchasedCard(UUID.fromString(purchasedGiftCardId));
                } else if (isGroupSession(session)) {
                    markGroupPaidFromStripeSession(session);
                } else {
                    markPaymentPaidFromStripeSession(session);
                }
            } else if ("checkout.session.expired".equals(eventType) ||
                    "checkout.session.async_payment_failed".equals(eventType)) {
                Session session = extractCheckoutSession(event);
                if (isGroupSession(session)) {
                    handleFailedOrExpiredGroupSession(session);
                } else {
                    handleFailedOrExpiredCheckoutSession(session);
                }
            }

            webhookEvent.setProcessed(true);
            webhookEvent.setProcessedAt(Instant.now());

        } catch (Exception ex) {
            webhookEvent.setProcessed(false);
            webhookEvent.setProcessingError(ex.getMessage());
        }

        PaymentWebhookEvent savedEvent = paymentWebhookEventRepository.save(webhookEvent);

        return new StripeWebhookResponse(
                savedEvent.getId(),
                savedEvent.getProvider(),
                savedEvent.getProviderEventId(),
                savedEvent.getEventType(),
                savedEvent.isProcessed(),
                savedEvent.isProcessed()
                        ? "Webhook processed successfully"
                        : "Webhook stored but processing failed"
        );
    }



    private void markPaymentPaidFromCheckoutSession(StripeWebhookRequest request) {
        if (request.providerCheckoutSessionId() == null ||
                request.providerCheckoutSessionId().trim().isEmpty()) {
            throw new BadRequestException("Checkout session id is required for completed checkout webhook");
        }

        Payment payment = paymentRepository
                .findByProviderAndProviderCheckoutSessionId(
                        PaymentProvider.STRIPE,
                        request.providerCheckoutSessionId().trim()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for checkout session"));

        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new BadRequestException("Payment cannot be marked paid from current status");
        }

        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setProviderPaymentIntentId(optionalTrim(request.providerPaymentIntentId()));

        if (request.paymentMethodType() != null) {
            payment.setPaymentMethodType(request.paymentMethodType());
        }

        payment.setPaidAt(java.time.Instant.now());
        paymentRepository.save(payment);

        // Route this (currently unwired, legacy) DTO webhook through the same hardened finalisation as
        // the live paths, so a downstream failure can't 500 it and it stays consistent with them.
        finalizePaidBooking(payment, payment.getBooking());
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

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private Session extractCheckoutSession(Event event) {
        StripeObject stripeObject = event.getData().getObject();

        if (!(stripeObject instanceof Session session)) {
            throw new BadRequestException("Stripe event object is not a checkout session");
        }

        return session;
    }

    private void markPaymentPaidFromStripeSession(Session session) {
        if (session.getId() == null || session.getId().trim().isEmpty()) {
            throw new BadRequestException("Checkout session id is missing");
        }

        Payment payment = paymentRepository
                .findByProviderAndProviderCheckoutSessionId(
                        PaymentProvider.STRIPE,
                        session.getId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for checkout session"));

        Booking booking = payment.getBooking();

        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            confirmBookingAfterPayment(booking);
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new BadRequestException("Payment cannot be marked paid from current status");
        }

        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setProviderPaymentIntentId(session.getPaymentIntent());
        payment.setPaidAt(Instant.now());

        paymentRepository.save(payment);

        // Safety net: the booking's hold may have expired and released the seat
        // before this (late) payment landed. If the booking can no longer be
        // honored, refund in full instead of confirming it.
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.ACCEPTED &&
                booking.getStatus() != BookingStatus.CONFIRMED) {
            refundFullPayment(payment, "Booking was released before the payment completed");
            return;
        }

        finalizePaidBooking(payment, booking);
    }

    @Transactional
    public void handleBookingCancellationPayment(
            Booking booking,
            BookingCancellationActor cancelledBy,
            String reason
    ) {
        handleBookingCancellationPayment(booking, cancelledBy, reason, null);
    }

    /**
     * Cancellation refund with an optional admin override. When {@code overrideRefundPercentage}
     * is null the refund is computed from the active cancellation-refund policy (the normal path);
     * when non-null it is used directly (0–100% of the booking total), still routed through the
     * same host-ledger clawback + gift/Stripe split so partial refunds stay reconciled.
     */
    @Transactional
    public void handleBookingCancellationPayment(
            Booking booking,
            BookingCancellationActor cancelledBy,
            String reason,
            BigDecimal overrideRefundPercentage
    ) {
        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElse(null);

        if (payment == null) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.PENDING ||
                payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
            // Return a reserved gift-card share so the balance isn't stranded on the dead payment.
            // Bundle members are excluded: their gift accounting is group-level (the share is either
            // returned once when the whole group dies, or refunded per-member if the group still pays).
            if (payment.getPaymentGroup() == null) {
                releaseGiftCardIfAny(payment);
            }
            payment.setPaymentStatus(PaymentStatus.CANCELLED);
            payment.setCancelledAt(Instant.now());
            payment.setRefundReason(optionalTrim(reason));
            paymentRepository.save(payment);
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
            return;
        }

        if (payment.getPaymentStatus() != PaymentStatus.PAID) {
            return;
        }

        RefundCalculationResult refundCalculation = overrideRefundPercentage != null
                ? buildOverrideRefund(booking, cancelledBy, overrideRefundPercentage)
                : cancellationRefundPolicyService.calculateRefund(booking, cancelledBy);

        BigDecimal refundAmount = refundCalculation.refundAmount();

        // Claw back the host's earning only in proportion to the refund the customer
        // actually receives. A 0% refund (e.g. a late cancellation) leaves the host's
        // earning fully intact; a 50% refund reverses half, and so on.
        BigDecimal refundFraction = refundCalculation.refundPercentage()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        hostLedgerService.reverseForPayment(payment.getId(), refundFraction, reason);

        payment.setRefundReason(optionalTrim(reason));

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            paymentRepository.save(payment);
            return;
        }

        applyRefundSplit(payment, refundFraction, refundAmount, reason);
    }

    /**
     * Splits a refund between the gift card and Stripe. The cash leg is capped at the cash Stripe
     * actually captured (amount − giftCardAmount) so we can never ask Stripe to over-refund, and it is
     * refunded via Stripe FIRST — the gift share returns to the card only once the cash refund
     * succeeds. That ordering means a Stripe failure leaves REFUND_FAILED with nothing credited to the
     * card (safe to retry), instead of the old order which credited the gift card before a cash refund
     * that could then fail and strand the customer's cash. Status is derived from the reconciled total
     * (gift + cash), so a gift+cash booking refunded in full reads REFUNDED, not PARTIALLY_REFUNDED.
     */
    private void applyRefundSplit(Payment payment, BigDecimal refundFraction, BigDecimal refundAmount, String reason) {
        BigDecimal giftCardAmount = payment.getGiftCardAmount() != null ? payment.getGiftCardAmount() : BigDecimal.ZERO;
        BigDecimal capturedCash = payment.getAmount().subtract(giftCardAmount).max(BigDecimal.ZERO);

        // When the payment completed WITHOUT a Stripe charge (gift card covered everything but a
        // sub-minimum remainder the platform absorbed), there is no payment intent anywhere — the
        // "cash" was never the customer's money. Refund the gift share only; asking Stripe for the
        // absorbed cents would fail the whole refund and strand the customer at EUR 0.
        boolean hasStripeCharge = optionalTrim(payment.getProviderPaymentIntentId()) != null
                || (payment.getPaymentGroup() != null
                && optionalTrim(payment.getPaymentGroup().getProviderPaymentIntentId()) != null);
        if (!hasStripeCharge) {
            capturedCash = BigDecimal.ZERO;
        }

        BigDecimal giftRefund = giftCardAmount.multiply(refundFraction)
                .setScale(2, RoundingMode.HALF_UP)
                .min(giftCardAmount)
                .max(BigDecimal.ZERO);
        BigDecimal cashRefund = refundAmount.subtract(giftRefund).max(BigDecimal.ZERO)
                .min(capturedCash)
                .setScale(2, RoundingMode.HALF_UP);

        // What the customer actually paid: captured cash + gift draw. The absorbed remainder of a
        // no-Stripe completion is excluded, so a full refund of a gift-covered booking reads
        // REFUNDED rather than PARTIALLY_REFUNDED.
        BigDecimal customerPaidTotal = capturedCash.add(giftCardAmount);

        // No cash leg — the gift-card return alone covers the refund.
        if (cashRefund.signum() <= 0) {
            if (giftRefund.signum() > 0) {
                giftCardService.returnToCard(payment.getGiftCardId(), giftRefund);
            }
            finishRefund(payment, giftRefund, null, customerPaidTotal);
            return;
        }

        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        paymentRepository.save(payment);

        try {
            PaymentRefundResult refundResult =
                    paymentCheckoutProvider.refundPayment(payment, cashRefund, reason);

            // Cash refunded — now (and only now) return the gift share to the card.
            if (giftRefund.signum() > 0) {
                giftCardService.returnToCard(payment.getGiftCardId(), giftRefund);
            }
            finishRefund(payment, refundResult.refundedAmount().add(giftRefund),
                    refundResult.providerRefundId(), customerPaidTotal);

        } catch (Exception ex) {
            // Stripe failed: nothing credited to the gift card, so no money is stranded. Safe to retry.
            payment.setPaymentStatus(PaymentStatus.REFUND_FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
        }
    }

    /**
     * Records a completed refund total and derives the status from the reconciled amount,
     * measured against what the customer actually paid (captured cash + gift draw — excludes
     * any platform-absorbed sub-minimum remainder).
     */
    private void finishRefund(Payment payment, BigDecimal totalRefunded, String providerRefundId,
                              BigDecimal customerPaidTotal) {
        if (providerRefundId != null) {
            payment.setProviderRefundId(providerRefundId);
        }
        payment.setRefundedAmount(totalRefunded);
        payment.setRefundedAt(Instant.now());
        payment.setPaymentStatus(totalRefunded.compareTo(customerPaidTotal) >= 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        paymentRepository.save(payment);
    }

    private void handleFailedOrExpiredCheckoutSession(Session session) {
        if (session.getId() == null || session.getId().trim().isEmpty()) {
            return;
        }

        Payment payment = paymentRepository
                .findByProviderAndProviderCheckoutSessionId(PaymentProvider.STRIPE, session.getId())
                .orElse(null);

        if (payment == null) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.PAID ||
                payment.getPaymentStatus() == PaymentStatus.REFUNDED ||
                payment.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.PENDING ||
                payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
            payment.setPaymentStatus(PaymentStatus.FAILED);
            payment.setFailedAt(Instant.now());
            payment.setFailureReason("Stripe checkout session expired or payment failed");
            releaseGiftCardIfAny(payment);
            paymentRepository.save(payment);
        }

        // Free the seat immediately instead of waiting for the periodic expiry sweep.
        bookingExpiryService.releaseBookingSlotAfterFailedPayment(payment.getBooking().getId());
    }

    /**
     * Fully refunds a paid booking, used when the platform/host cancels (e.g. a
     * slot is cancelled for not meeting its minimum). Pending/processing payments
     * are simply cancelled.
     */
    @Transactional
    public void fullyRefundBookingPayment(Booking booking, String reason) {
        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElse(null);

        if (payment == null) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.PENDING ||
                payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
            // Same rule as handleBookingCancellationPayment: return a reserved gift-card share
            // so the balance isn't stranded on the dead payment (bundle members excluded —
            // their gift accounting is group-level).
            if (payment.getPaymentGroup() == null) {
                releaseGiftCardIfAny(payment);
            }
            payment.setPaymentStatus(PaymentStatus.CANCELLED);
            payment.setCancelledAt(Instant.now());
            payment.setRefundReason(optionalTrim(reason));
            paymentRepository.save(payment);
            return;
        }

        if (payment.getPaymentStatus() != PaymentStatus.PAID) {
            return;
        }

        hostLedgerService.reverseForPayment(payment.getId(), reason);
        refundFullPayment(payment, reason);
    }

    private void refundFullPayment(Payment payment, String reason) {
        // A full refund is a 100% split: the gift share returns to the card and the captured cash is
        // refunded via Stripe. Routing through applyRefundSplit makes it gift-aware — the old version
        // asked Stripe to refund the whole amount, which Stripe rejects for a gift-part-paid booking.
        payment.setRefundReason(optionalTrim(reason));
        applyRefundSplit(payment, BigDecimal.ONE, payment.getAmount(), reason);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getAdminPaymentById(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        return toResponse(payment);
    }

    /** Payment for a booking, or null when the booking has no payment (e.g. an offline admin booking). */
    @Transactional(readOnly = true)
    public PaymentResponse getAdminPaymentByBookingId(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .map(this::toResponse)
                .orElse(null);
    }

    /** Builds a refund result from an admin-chosen percentage of the booking total (0–100), capped. */
    private RefundCalculationResult buildOverrideRefund(
            Booking booking,
            BookingCancellationActor cancelledBy,
            BigDecimal overridePercentage
    ) {
        BigDecimal pct = overridePercentage
                .max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal bookingAmount = booking.getTotalAmount() != null
                ? booking.getTotalAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal refundAmount = bookingAmount
                .multiply(pct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .min(bookingAmount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new RefundCalculationResult(cancelledBy, BigDecimal.ZERO, pct, refundAmount);
    }



    private void confirmBookingAfterPayment(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT ||
                booking.getStatus() == BookingStatus.ACCEPTED) {
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
            // The confirmation notification (email/WhatsApp/in-app, with one-tap wallet + calendar links)
            // is best-effort: a notifier failure must never roll back the confirmation. Send it after
            // this transaction commits, isolated, and only when we actually transitioned the booking
            // (so a duplicate webhook delivery doesn't re-notify).
            UUID bookingId = booking.getId();
            afterCommit(() -> safely("confirmation notification", bookingId,
                    () -> paidBookingFinalizer.sendConfirmationNotification(bookingId)));
        }
    }


    private void validateBookingReadyForCheckout(Booking booking) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.ACCEPTED) {
            throw new BadRequestException("Checkout can be created only for bookings pending payment");
        }
    }

    // ------------------------------------------------------------------
    // Bundle checkout (payment groups): one Stripe session, many bookings
    // ------------------------------------------------------------------

    /**
     * Creates ONE checkout covering several bookings (e.g. "book my whole AI trip plan").
     * Mirrors {@link #createCheckout}: the group + member payments + gift-card reserve happen in
     * one transaction, the Stripe call runs OUTSIDE any transaction, and the result is attached
     * in a follow-up transaction. On a Stripe failure the group's gift-card draw is returned and
     * the member payments stay PENDING for the expiry sweep to clean up.
     *
     * @param loggedInUserId owner for logged-in bundles (null for guest bundles)
     * @param guestEmail     owner email for guest bundles (null for logged-in bundles)
     */
    public PaymentGroupResponse createGroupCheckout(
            UUID loggedInUserId,
            String guestEmail,
            List<UUID> bookingIds,
            String giftCardCode,
            UUID tripPlanId
    ) {
        GroupCheckoutPreparation preparation = paymentTransactionService.prepareGroupForCheckout(
                loggedInUserId, guestEmail, bookingIds, giftCardCode, tripPlanId);

        PaymentGroup group = preparation.group();

        BigDecimal giftCardAmount = group.getGiftCardAmount() != null
                ? group.getGiftCardAmount() : BigDecimal.ZERO;
        BigDecimal cashRemainder = group.getTotalAmount().subtract(giftCardAmount);
        if (giftCardAmount.signum() > 0 && cashRemainder.compareTo(stripeMinimumCharge) < 0) {
            return self.completeGroupWithoutStripeCharge(group.getId());
        }

        try {
            PaymentCheckoutResult checkoutResult =
                    paymentCheckoutProvider.createGroupCheckout(group, preparation.payments());
            return self.attachGroupCheckoutResult(group.getId(), checkoutResult);
        } catch (RuntimeException ex) {
            self.releaseGroupGiftCardOnFailure(group.getId());
            throw ex;
        }
    }

    @Transactional
    public PaymentGroupResponse attachGroupCheckoutResult(UUID groupId, PaymentCheckoutResult checkoutResult) {
        PaymentGroup group = paymentGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment group not found"));

        group.setStatus(PaymentGroupStatus.PROCESSING);
        group.setCheckoutUrl(checkoutResult.checkoutUrl());
        group.setProviderCheckoutSessionId(checkoutResult.providerCheckoutSessionId());
        group.setProviderPaymentIntentId(checkoutResult.providerPaymentIntentId());
        paymentGroupRepository.save(group);

        List<Payment> members = paymentRepository.findByPaymentGroupIdOrderByCreatedAtAsc(groupId);
        for (Payment member : members) {
            if (member.getPaymentStatus() == PaymentStatus.PENDING) {
                member.setPaymentStatus(PaymentStatus.PROCESSING);
                // The member deliberately does NOT get the session id / checkout url: the Stripe
                // session belongs to the group (and the payments partial-unique indexes must hold).
            }
        }
        paymentRepository.saveAll(members);

        return toGroupResponse(group, members);
    }

    /** Gift card covers the whole bundle (or all but a sub-minimum remainder): no Stripe session. */
    @Transactional
    public PaymentGroupResponse completeGroupWithoutStripeCharge(UUID groupId) {
        PaymentGroup group = paymentGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment group not found"));

        List<Payment> members = paymentRepository.findByPaymentGroupIdOrderByCreatedAtAsc(groupId);

        group.setStatus(PaymentGroupStatus.PAID);
        group.setPaidAt(Instant.now());
        paymentGroupRepository.save(group);

        for (Payment member : members) {
            if (member.getPaymentStatus() == PaymentStatus.PAID) {
                confirmBookingAfterPayment(member.getBooking());
                continue;
            }
            member.setPaymentStatus(PaymentStatus.PAID);
            member.setPaidAt(Instant.now());
            paymentRepository.save(member);
            finalizePaidBooking(member, member.getBooking());
        }

        return toGroupResponse(group, members);
    }

    /** Public status view of a bundle checkout, addressed by its unguessable token. */
    @Transactional(readOnly = true)
    public PaymentGroupResponse getPaymentGroupByToken(String groupToken) {
        PaymentGroup group = paymentGroupRepository.findByGroupToken(groupToken)
                .orElseThrow(() -> new ResourceNotFoundException("Payment group not found"));
        List<Payment> members = paymentRepository.findByPaymentGroupIdOrderByCreatedAtAsc(group.getId());
        return toGroupResponse(group, members);
    }

    @Transactional
    public void releaseGroupGiftCardOnFailure(UUID groupId) {
        paymentGroupRepository.findByIdForUpdate(groupId).ifPresent(group -> {
            if (group.getStatus() == PaymentGroupStatus.PENDING
                    || group.getStatus() == PaymentGroupStatus.PROCESSING) {
                releaseGroupGiftCardIfAny(group);
                paymentGroupRepository.save(group);
            }
        });
    }

    /**
     * Returns the group's whole gift-card draw exactly once. Idempotency comes from the
     * giftCardReturnedAt marker instead of zeroing giftCardAmount, because the original draw
     * stays the basis for "cash actually captured" refund math on late payments.
     */
    private void releaseGroupGiftCardIfAny(PaymentGroup group) {
        if (group.getGiftCardId() != null
                && group.getGiftCardAmount() != null
                && group.getGiftCardAmount().signum() > 0
                && group.getGiftCardReturnedAt() == null) {
            giftCardService.returnToCard(group.getGiftCardId(), group.getGiftCardAmount());
            group.setGiftCardReturnedAt(Instant.now());
        }
    }

    private boolean isGroupSession(Session session) {
        String groupId = session.getMetadata() != null
                ? session.getMetadata().get("paymentGroupId") : null;
        return groupId != null && !groupId.isBlank();
    }

    private void markGroupPaidFromStripeSession(Session session) {
        if (session.getId() == null || session.getId().trim().isEmpty()) {
            throw new BadRequestException("Checkout session id is missing");
        }

        // Locking read FIRST (not a re-read): the expiry sweep can be cancelling this same
        // group right now — the row lock serializes the two, and because this is the first
        // load of the entity in this transaction, the status/gift fields are post-lock fresh.
        PaymentGroup group = paymentGroupRepository
                .findBySessionIdForUpdate(PaymentProvider.STRIPE, session.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment group not found for checkout session"));

        List<Payment> members = paymentRepository.findByPaymentGroupIdOrderByCreatedAtAsc(group.getId());

        if (group.getStatus() == PaymentGroupStatus.PAID) {
            // Duplicate delivery: re-run only the idempotent confirmations.
            for (Payment member : members) {
                if (member.getPaymentStatus() == PaymentStatus.PAID) {
                    confirmBookingAfterPayment(member.getBooking());
                }
            }
            return;
        }

        if (group.getStatus() == PaymentGroupStatus.REFUNDED) {
            throw new BadRequestException("Payment group cannot be marked paid from current status");
        }

        if (group.getStatus() == PaymentGroupStatus.CANCELLED
                || group.getStatus() == PaymentGroupStatus.FAILED
                || group.getStatus() == PaymentGroupStatus.REFUND_FAILED) {
            // Late payment on a bundle that was already released (or a retried delivery after a
            // failed refund): the seats are gone and the gift-card draw was already returned, so
            // refund ALL captured cash once, in full. A provider failure must NOT vanish into the
            // webhook's catch-all — record REFUND_FAILED on the group so it is visible and
            // retryable instead of silently keeping the customer's money.
            group.setProviderPaymentIntentId(session.getPaymentIntent());
            BigDecimal capturedCash = group.getTotalAmount()
                    .subtract(group.getGiftCardAmount() != null ? group.getGiftCardAmount() : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO);
            try {
                if (capturedCash.signum() > 0) {
                    paymentCheckoutProvider.refundGroupCash(
                            group, capturedCash, "Bundle was released before the payment completed");
                }
                group.setStatus(PaymentGroupStatus.REFUNDED);
                group.setFailureReason("Late payment refunded in full — the bundle had already been released");
            } catch (Exception ex) {
                log.error("Late-payment refund of {} {} failed for payment group {} — customer cash is "
                                + "still held; refund must be retried.",
                        capturedCash, group.getCurrency(), group.getId(), ex);
                group.setStatus(PaymentGroupStatus.REFUND_FAILED);
                group.setFailureReason("Late payment refund failed: " + ex.getMessage());
            }
            paymentGroupRepository.save(group);
            return;
        }

        group.setStatus(PaymentGroupStatus.PAID);
        group.setPaidAt(Instant.now());
        group.setProviderPaymentIntentId(session.getPaymentIntent());
        paymentGroupRepository.save(group);

        for (Payment member : members) {
            Booking booking = member.getBooking();

            if (member.getPaymentStatus() == PaymentStatus.PAID) {
                confirmBookingAfterPayment(booking);
                continue;
            }

            boolean bookingHonorable = booking.getStatus() == BookingStatus.PENDING_PAYMENT ||
                    booking.getStatus() == BookingStatus.ACCEPTED ||
                    booking.getStatus() == BookingStatus.CONFIRMED;
            boolean memberOpen = member.getPaymentStatus() == PaymentStatus.PENDING ||
                    member.getPaymentStatus() == PaymentStatus.PROCESSING;

            if (!bookingHonorable || !memberOpen) {
                // A member was released mid-checkout (e.g. the traveler cancelled one booking) but
                // the customer paid for the full bundle: return this member's share (its gift part
                // back to the card, its cash part as a partial refund on the shared intent) and
                // keep confirming the healthy members.
                if (member.getPaymentStatus() != PaymentStatus.REFUNDED &&
                        member.getPaymentStatus() != PaymentStatus.PARTIALLY_REFUNDED &&
                        member.getPaymentStatus() != PaymentStatus.REFUND_PENDING) {
                    refundFullPayment(member, "Booking was released before the bundle payment completed");
                }
                continue;
            }

            member.setPaymentStatus(PaymentStatus.PAID);
            member.setPaidAt(Instant.now());
            paymentRepository.save(member);
            finalizePaidBooking(member, booking);
        }
    }

    private void handleFailedOrExpiredGroupSession(Session session) {
        if (session.getId() == null || session.getId().trim().isEmpty()) {
            return;
        }

        // Locking read FIRST — see markGroupPaidFromStripeSession for why.
        PaymentGroup group = paymentGroupRepository
                .findBySessionIdForUpdate(PaymentProvider.STRIPE, session.getId())
                .orElse(null);

        if (group == null) {
            return;
        }

        if (group.getStatus() != PaymentGroupStatus.PENDING
                && group.getStatus() != PaymentGroupStatus.PROCESSING) {
            return;
        }

        group.setStatus(PaymentGroupStatus.FAILED);
        group.setFailedAt(Instant.now());
        group.setFailureReason("Stripe checkout session expired or payment failed");
        releaseGroupGiftCardIfAny(group);
        paymentGroupRepository.save(group);

        List<Payment> members = paymentRepository.findByPaymentGroupIdOrderByCreatedAtAsc(group.getId());
        for (Payment member : members) {
            if (member.getPaymentStatus() == PaymentStatus.PENDING ||
                    member.getPaymentStatus() == PaymentStatus.PROCESSING) {
                member.setPaymentStatus(PaymentStatus.FAILED);
                member.setFailedAt(Instant.now());
                member.setFailureReason("Stripe checkout session expired or payment failed");
                // No per-member gift release: the group-level return above covered the full draw.
            }
        }
        paymentRepository.saveAll(members);

        for (Payment member : members) {
            bookingExpiryService.releaseBookingSlotAfterFailedPayment(member.getBooking().getId());
        }
    }

    private PaymentGroupResponse toGroupResponse(PaymentGroup group, List<Payment> members) {
        List<PaymentGroupMemberResponse> memberResponses = members.stream()
                .map(member -> {
                    Booking booking = member.getBooking();
                    return new PaymentGroupMemberResponse(
                            booking.getId(),
                            booking.getBookingReference(),
                            booking.getExperience() != null ? booking.getExperience().getTitle() : null,
                            booking.getAvailabilitySlot() != null ? booking.getAvailabilitySlot().getStartTime() : null,
                            booking.getStatus() != null ? booking.getStatus().name() : null,
                            member.getPaymentStatus(),
                            member.getAmount()
                    );
                })
                .toList();

        return new PaymentGroupResponse(
                group.getGroupToken(),
                group.getStatus(),
                group.getTotalAmount(),
                group.getGiftCardAmount(),
                group.getCurrency(),
                group.getCheckoutUrl(),
                group.getCreatedAt(),
                group.getPaidAt(),
                memberResponses
        );
    }
}