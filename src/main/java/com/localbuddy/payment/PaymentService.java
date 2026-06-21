package com.localbuddy.payment;

import com.localbuddy.booking.*;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.invoice.InvoiceService;
import com.localbuddy.payout.HostLedgerService;
import com.localbuddy.pricing.PricingEngine;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.referral.ReferralService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final BigDecimal commissionPercentage;
    private final PaymentCheckoutProvider paymentCheckoutProvider;
    private final PaymentWebhookEventRepository paymentWebhookEventRepository;
    private final PromoCodeService promoCodeService;
    private final ReferralService referralService;
    private final CancellationRefundPolicyService cancellationRefundPolicyService;
    private final PaymentTransactionService paymentTransactionService;
    private final BookingExpiryService bookingExpiryService;
    private final PricingEngine pricingEngine;
    private final HostLedgerService hostLedgerService;
    private final InvoiceService invoiceService;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          @Value("${app.platform.commission-percentage:20}") BigDecimal commissionPercentage, PaymentCheckoutProvider paymentCheckoutProvider, PaymentWebhookEventRepository paymentWebhookEventRepository, PromoCodeService promoCodeService, ReferralService referralService, CancellationRefundPolicyService cancellationRefundPolicyService, PaymentTransactionService paymentTransactionService, BookingExpiryService bookingExpiryService, PricingEngine pricingEngine, HostLedgerService hostLedgerService, InvoiceService invoiceService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.commissionPercentage = commissionPercentage;
        this.paymentCheckoutProvider = paymentCheckoutProvider;
        this.paymentWebhookEventRepository = paymentWebhookEventRepository;
        this.promoCodeService = promoCodeService;
        this.referralService = referralService;
        this.cancellationRefundPolicyService = cancellationRefundPolicyService;
        this.paymentTransactionService = paymentTransactionService;
        this.bookingExpiryService = bookingExpiryService;
        this.pricingEngine = pricingEngine;
        this.hostLedgerService = hostLedgerService;
        this.invoiceService = invoiceService;
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
        if (booking.getTravelerUser() != null &&
                booking.getTravelerUser().getId().equals(userId)) {
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

        if (booking.getBookingSource() != BookingSource.GUEST) {
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

        if (booking.getBookingSource() != BookingSource.GUEST) {
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
                request.bookingId()
        );

        if (payment.getPaymentStatus() == PaymentStatus.PROCESSING &&
                payment.getCheckoutUrl() != null &&
                !payment.getCheckoutUrl().trim().isEmpty()) {
            return toCheckoutResponse(payment);
        }

        PaymentCheckoutResult checkoutResult = paymentCheckoutProvider.createCheckout(payment);

        Payment savedPayment = paymentTransactionService.attachCheckoutResult(
                payment.getId(),
                checkoutResult
        );

        return toCheckoutResponse(savedPayment);
    }



    @Transactional
    public PaymentCheckoutResponse createGuestCheckout(CreateGuestPaymentRequest request) {
        String normalizedReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String normalizedEmail = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));

        if (booking.getBookingSource() != BookingSource.GUEST) {
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
                markPaymentPaidFromStripeSession(session);
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

        Booking booking = payment.getBooking();
        confirmBookingAfterPayment(booking);
        hostLedgerService.recordEarning(booking, payment);
        invoiceService.generateForConfirmedPayment(booking, payment);
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

        promoCodeService.redeemPromoCodeForPaidBooking(booking);
        referralService.redeemReferralCodeForPaidBooking(booking);

        confirmBookingAfterPayment(booking);
        hostLedgerService.recordEarning(booking, payment);
        invoiceService.generateForConfirmedPayment(booking, payment);
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

        hostLedgerService.reverseForPayment(payment.getId(), reason);

        RefundCalculationResult refundCalculation =
                cancellationRefundPolicyService.calculateRefund(booking, cancelledBy);

        BigDecimal refundAmount = refundCalculation.refundAmount();

        payment.setRefundReason(optionalTrim(reason));

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            paymentRepository.save(payment);
            return;
        }

        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        paymentRepository.save(payment);

        try {
            PaymentRefundResult refundResult =
                    paymentCheckoutProvider.refundPayment(payment, refundAmount, reason);

            payment.setProviderRefundId(refundResult.providerRefundId());
            payment.setRefundedAmount(refundResult.refundedAmount());
            payment.setPaymentStatus(refundResult.paymentStatus());

            if (refundResult.paymentStatus() == PaymentStatus.REFUNDED ||
                    refundResult.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
                payment.setRefundedAt(Instant.now());
            }

            paymentRepository.save(payment);

        } catch (Exception ex) {
            payment.setPaymentStatus(PaymentStatus.REFUND_FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
        }
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
        BigDecimal refundAmount = payment.getAmount();

        payment.setRefundReason(optionalTrim(reason));
        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        paymentRepository.save(payment);

        try {
            PaymentRefundResult refundResult =
                    paymentCheckoutProvider.refundPayment(payment, refundAmount, reason);

            payment.setProviderRefundId(refundResult.providerRefundId());
            payment.setRefundedAmount(refundResult.refundedAmount());
            payment.setPaymentStatus(refundResult.paymentStatus());

            if (refundResult.paymentStatus() == PaymentStatus.REFUNDED ||
                    refundResult.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
                payment.setRefundedAt(Instant.now());
            }

            paymentRepository.save(payment);

        } catch (Exception ex) {
            payment.setPaymentStatus(PaymentStatus.REFUND_FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
        }
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
        }
    }


    private void validateBookingReadyForCheckout(Booking booking) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT &&
                booking.getStatus() != BookingStatus.ACCEPTED) {
            throw new BadRequestException("Checkout can be created only for bookings pending payment");
        }
    }
}