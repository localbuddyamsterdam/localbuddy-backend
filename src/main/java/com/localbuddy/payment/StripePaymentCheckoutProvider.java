package com.localbuddy.payment;

import com.localbuddy.common.exception.BadRequestException;
import com.stripe.StripeClient;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Component
public class StripePaymentCheckoutProvider implements PaymentCheckoutProvider {

    // Stripe requires a checkout session to live at least 30 minutes; we add a
    // small buffer over that floor. The booking expiry job proactively expires
    // the session earlier (at the internal hold), so this is only a fallback.
    private static final long SESSION_EXPIRATION_SECONDS = 31L * 60L;

    private final StripeProperties stripeProperties;
    private final StripeClient stripeClient;


    public StripePaymentCheckoutProvider(StripeProperties stripeProperties, StripeClient stripeClient) {
        this.stripeProperties = stripeProperties;
        this.stripeClient = stripeClient;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public PaymentCheckoutResult createCheckout(Payment payment) {
        if (stripeProperties.secretKey() == null || stripeProperties.secretKey().trim().isEmpty()) {
            throw new BadRequestException("Stripe secret key is not configured");
        }

        try {

            long amountInCents = payment.getAmount()
                    .movePointRight(2)
                    .longValueExact();

            String successUrl = stripeProperties.successUrl()
                    + "?paymentId=" + payment.getId()
                    + "&session_id={CHECKOUT_SESSION_ID}";

            String cancelUrl = stripeProperties.cancelUrl()
                    + "?paymentId=" + payment.getId();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setExpiresAt(Instant.now().getEpochSecond() + SESSION_EXPIRATION_SECONDS)
                    .setClientReferenceId(payment.getId().toString())
                    .putMetadata("paymentId", payment.getId().toString())
                    .putMetadata("bookingId", payment.getBooking().getId().toString())
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency(payment.getCurrency().toLowerCase())
                                                    .setUnitAmount(amountInCents)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName("LocalBuddy booking " + payment.getBooking().getBookingReference())
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            Session session = stripeClient.checkout().sessions().create(params);

            return new PaymentCheckoutResult(
                    session.getUrl(),
                    session.getId(),
                    session.getPaymentIntent(),
                    PaymentMethodType.UNKNOWN
            );

        } catch (Exception ex) {
            throw new BadRequestException("Unable to create Stripe checkout session: " + ex.getMessage());
        }
    }

    @Override
    public PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason) {
        if (payment.getProviderPaymentIntentId() == null ||
                payment.getProviderPaymentIntentId().trim().isEmpty()) {
            throw new BadRequestException("Stripe payment intent id is missing");
        }

        BigDecimal normalizedRefundAmount = refundAmount == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : refundAmount.setScale(2, RoundingMode.HALF_UP);

        if (normalizedRefundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new PaymentRefundResult(
                    null,
                    payment.getPaymentStatus(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            );
        }

        if (normalizedRefundAmount.compareTo(payment.getAmount()) > 0) {
            throw new BadRequestException("Refund amount cannot be greater than payment amount");
        }

        try {

            long refundAmountInCents = normalizedRefundAmount
                    .movePointRight(2)
                    .longValueExact();

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(payment.getProviderPaymentIntentId())
                    .setAmount(refundAmountInCents)
                    .setReason(resolveStripeRefundReason(reason))
                    .build();

            Refund refund = stripeClient.refunds().create(params);

            PaymentStatus status = "succeeded".equalsIgnoreCase(refund.getStatus())
                    ? PaymentStatus.REFUNDED
                    : PaymentStatus.REFUND_PENDING;

            if (normalizedRefundAmount.compareTo(payment.getAmount()) < 0 &&
                    status == PaymentStatus.REFUNDED) {
                status = PaymentStatus.PARTIALLY_REFUNDED;
            }

            return new PaymentRefundResult(
                    refund.getId(),
                    status,
                    normalizedRefundAmount
            );

        } catch (Exception ex) {
            throw new BadRequestException("Unable to refund Stripe payment: " + ex.getMessage());
        }
    }

    @Override
    public void expireCheckout(String providerCheckoutSessionId) {
        if (providerCheckoutSessionId == null || providerCheckoutSessionId.trim().isEmpty()) {
            return;
        }

        try {
            stripeClient.checkout().sessions().expire(providerCheckoutSessionId.trim());
        } catch (Exception ex) {
            // Best-effort: the session may already be completed or expired.
        }
    }

    private RefundCreateParams.Reason resolveStripeRefundReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        }

        String value = reason.trim().toLowerCase();

        if (value.contains("duplicate")) {
            return RefundCreateParams.Reason.DUPLICATE;
        }

        if (value.contains("fraud")) {
            return RefundCreateParams.Reason.FRAUDULENT;
        }

        return RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
    }
}