package com.localbuddy.payment;

import com.localbuddy.common.exception.BadRequestException;
import com.stripe.StripeClient;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Component
public class StripePaymentCheckoutProvider implements PaymentCheckoutProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentCheckoutProvider.class);

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

            // Charge the customer only the portion not covered by an applied gift card.
            java.math.BigDecimal chargeAmount = payment.getAmount()
                    .subtract(payment.getGiftCardAmount() != null
                            ? payment.getGiftCardAmount() : java.math.BigDecimal.ZERO);
            long amountInCents = chargeAmount
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
            log.error("Stripe checkout session creation failed", ex);
            throw new BadRequestException("Unable to start the payment — please try again");
        }
    }

    @Override
    public PaymentCheckoutResult createGroupCheckout(PaymentGroup group, java.util.List<Payment> payments) {
        if (stripeProperties.secretKey() == null || stripeProperties.secretKey().trim().isEmpty()) {
            throw new BadRequestException("Stripe secret key is not configured");
        }
        if (payments == null || payments.isEmpty()) {
            throw new BadRequestException("A group checkout needs at least one payment");
        }

        try {
            BigDecimal giftCardAmount = group.getGiftCardAmount() != null
                    ? group.getGiftCardAmount() : BigDecimal.ZERO;
            BigDecimal chargeAmount = group.getTotalAmount().subtract(giftCardAmount);

            String successUrl = stripeProperties.successUrl()
                    + "?groupToken=" + group.getGroupToken()
                    + "&session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = stripeProperties.cancelUrl()
                    + "?groupToken=" + group.getGroupToken();

            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setExpiresAt(Instant.now().getEpochSecond() + SESSION_EXPIRATION_SECONDS)
                    .setClientReferenceId(group.getId().toString())
                    .putMetadata("paymentGroupId", group.getId().toString());

            if (giftCardAmount.signum() > 0) {
                // With a gift card applied there is no per-booking split of the remaining cash,
                // so present one aggregated line for exactly the amount Stripe should capture.
                builder.addLineItem(lineItem(
                        group.getCurrency(),
                        chargeAmount,
                        "LocalBuddy trip (" + payments.size() + " bookings, gift card applied)"
                ));
            } else {
                for (Payment payment : payments) {
                    builder.addLineItem(lineItem(
                            group.getCurrency(),
                            payment.getAmount(),
                            "LocalBuddy booking " + payment.getBooking().getBookingReference()
                    ));
                }
            }

            Session session = stripeClient.checkout().sessions().create(builder.build());

            return new PaymentCheckoutResult(
                    session.getUrl(),
                    session.getId(),
                    session.getPaymentIntent(),
                    PaymentMethodType.UNKNOWN
            );

        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Stripe group checkout session creation failed", ex);
            throw new BadRequestException("Unable to start the payment — please try again");
        }
    }

    private SessionCreateParams.LineItem lineItem(String currency, BigDecimal amount, String name) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currency.toLowerCase())
                                .setUnitAmount(amount.movePointRight(2).longValueExact())
                                .setProductData(
                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                .setName(name)
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    @Override
    public PaymentCheckoutResult createGiftCardCheckout(UUID giftCardId, BigDecimal amount, String currency, String reference) {
        if (stripeProperties.secretKey() == null || stripeProperties.secretKey().trim().isEmpty()) {
            throw new BadRequestException("Stripe secret key is not configured");
        }

        try {
            long amountInCents = amount.movePointRight(2).longValueExact();

            String successUrl = stripeProperties.successUrl()
                    + "?giftCardId=" + giftCardId
                    + "&session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = stripeProperties.cancelUrl() + "?giftCardId=" + giftCardId;

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setExpiresAt(Instant.now().getEpochSecond() + SESSION_EXPIRATION_SECONDS)
                    .setClientReferenceId(giftCardId.toString())
                    .putMetadata("giftCardId", giftCardId.toString())
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency(currency.toLowerCase())
                                                    .setUnitAmount(amountInCents)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName("LocalBuddy gift card " + reference)
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
            log.error("Stripe gift card checkout session creation failed", ex);
            throw new BadRequestException("Unable to start the payment — please try again");
        }
    }

    @Override
    public PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason) {
        // A bundle member's cash was captured on the group's shared payment intent, so a
        // per-booking refund becomes a partial refund of that intent by the member's amount.
        String paymentIntentId = payment.getProviderPaymentIntentId();
        if ((paymentIntentId == null || paymentIntentId.trim().isEmpty())
                && payment.getPaymentGroup() != null) {
            paymentIntentId = payment.getPaymentGroup().getProviderPaymentIntentId();
        }
        if (paymentIntentId == null || paymentIntentId.trim().isEmpty()) {
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
                    .setPaymentIntent(paymentIntentId.trim())
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
            log.error("Stripe refund failed", ex);
            throw new BadRequestException("Unable to process the refund — please try again");
        }
    }

    @Override
    public PaymentRefundResult refundGroupCash(PaymentGroup group, BigDecimal refundAmount, String reason) {
        if (group.getProviderPaymentIntentId() == null ||
                group.getProviderPaymentIntentId().trim().isEmpty()) {
            throw new BadRequestException("Stripe payment intent id is missing for the payment group");
        }

        BigDecimal normalizedRefundAmount = refundAmount == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : refundAmount.setScale(2, RoundingMode.HALF_UP);

        if (normalizedRefundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new PaymentRefundResult(null, PaymentStatus.REFUNDED,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(group.getProviderPaymentIntentId().trim())
                    .setAmount(normalizedRefundAmount.movePointRight(2).longValueExact())
                    .setReason(resolveStripeRefundReason(reason))
                    .build();

            Refund refund = stripeClient.refunds().create(params);

            return new PaymentRefundResult(
                    refund.getId(),
                    PaymentStatus.REFUNDED,
                    normalizedRefundAmount
            );

        } catch (Exception ex) {
            log.error("Stripe group refund failed", ex);
            throw new BadRequestException("Unable to process the refund — please try again");
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