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
                          @Value("${app.platform.commission-percentage:20}") BigDecimal commissionPercentage, PaymentCheckoutProvider paymentCheckoutProvider, PaymentWebhookEventRepository paymentWebhookEventRepository, CancellationRefundPolicyService cancellationRefundPolicyService, PaymentTransactionService paymentTransactionService, BookingExpiryService bookingExpiryService, PricingEngine pricingEngine, HostLedgerService hostLedgerService, GiftCardService giftCardService, PaidBookingFinalizer paidBookingFinalizer, @Value("${app.payments.stripe.minimum-charge:0.50}") BigDecimal stripeMinimumCharge) {
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
                payment.getUpdatedAt()
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
                } else {
                    markPaymentPaidFromStripeSession(session);
                }
            } else if ("checkout.session.expired".equals(eventType) ||
                    "checkout.session.async_payment_failed".equals(eventType)) {
                Session session = extractCheckoutSession(event);
                handleFailedOrExpiredCheckoutSession(session);
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
        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElse(null);

        if (payment == null) {
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.PENDING ||
                payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
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

        RefundCalculationResult refundCalculation =
                cancellationRefundPolicyService.calculateRefund(booking, cancelledBy);

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

        BigDecimal giftRefund = giftCardAmount.multiply(refundFraction)
                .setScale(2, RoundingMode.HALF_UP)
                .min(giftCardAmount)
                .max(BigDecimal.ZERO);
        BigDecimal cashRefund = refundAmount.subtract(giftRefund).max(BigDecimal.ZERO)
                .min(capturedCash)
                .setScale(2, RoundingMode.HALF_UP);

        // No cash leg — the gift-card return alone covers the refund.
        if (cashRefund.signum() <= 0) {
            if (giftRefund.signum() > 0) {
                giftCardService.returnToCard(payment.getGiftCardId(), giftRefund);
            }
            finishRefund(payment, giftRefund, null);
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
            finishRefund(payment, refundResult.refundedAmount().add(giftRefund), refundResult.providerRefundId());

        } catch (Exception ex) {
            // Stripe failed: nothing credited to the gift card, so no money is stranded. Safe to retry.
            payment.setPaymentStatus(PaymentStatus.REFUND_FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
        }
    }

    /** Records a completed refund total and derives the status from the reconciled amount. */
    private void finishRefund(Payment payment, BigDecimal totalRefunded, String providerRefundId) {
        if (providerRefundId != null) {
            payment.setProviderRefundId(providerRefundId);
        }
        payment.setRefundedAmount(totalRefunded);
        payment.setRefundedAt(Instant.now());
        payment.setPaymentStatus(totalRefunded.compareTo(payment.getAmount()) >= 0
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
}