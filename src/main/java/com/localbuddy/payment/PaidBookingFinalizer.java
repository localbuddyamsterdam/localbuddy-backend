package com.localbuddy.payment;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingConfirmationNotifier;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.giftcard.GiftCardService;
import com.localbuddy.invoice.InvoiceService;
import com.localbuddy.payout.HostLedgerService;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.referral.ReferralService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Runs the reconcilable, best-effort side-effects of a paid booking (promo/referral redemption,
 * host-ledger earning, gift-card redemption, invoices, the confirmation notification) — everything
 * that happens <em>after</em> the money is captured and the booking is CONFIRMED.
 *
 * <p>Each step runs in its own {@link Propagation#REQUIRES_NEW} transaction, on purpose. These
 * downstream services are themselves {@code @Transactional}, and Spring marks a transaction
 * rollback-only the moment a participating {@code @Transactional} call throws. If these ran inside
 * the caller's (booking-confirming) transaction, a single failing step would roll the whole thing
 * back and surface an {@code UnexpectedRollbackException} — turning a successful payment into a
 * customer-facing 500 and un-confirming an already-paid booking. Isolating each step means a
 * failure rolls back only that step; {@link PaymentService} catches it, logs it for reconciliation,
 * and the paid + confirmed booking stands.
 *
 * <p>Steps take ids and re-load their entities so they are safe to invoke after the confirming
 * transaction has committed (see {@link PaymentService#afterCommit}): the rows are then visible and
 * the entities are managed within this step's own transaction, so lazy associations initialise
 * normally. A step throws on failure; the orchestrator in {@link PaymentService} decides how to
 * report it. Same idea as {@link com.localbuddy.attendance.AttendanceCheckInWriter}.
 */
@Component
public class PaidBookingFinalizer {

    private final PromoCodeService promoCodeService;
    private final ReferralService referralService;
    private final HostLedgerService hostLedgerService;
    private final GiftCardService giftCardService;
    private final InvoiceService invoiceService;
    private final BookingConfirmationNotifier bookingConfirmationNotifier;
    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;

    public PaidBookingFinalizer(PromoCodeService promoCodeService,
                                ReferralService referralService,
                                HostLedgerService hostLedgerService,
                                GiftCardService giftCardService,
                                InvoiceService invoiceService,
                                BookingConfirmationNotifier bookingConfirmationNotifier,
                                PaymentRepository paymentRepository,
                                BookingRepository bookingRepository) {
        this.promoCodeService = promoCodeService;
        this.referralService = referralService;
        this.hostLedgerService = hostLedgerService;
        this.giftCardService = giftCardService;
        this.invoiceService = invoiceService;
        this.bookingConfirmationNotifier = bookingConfirmationNotifier;
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
    }

    /** Records the promo-code redemption (idempotent); the discount was already priced into the charge. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redeemPromoCode(UUID bookingId) {
        promoCodeService.redeemPromoCodeForPaidBooking(requireBooking(bookingId));
    }

    /** Records the referral redemption (idempotent). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redeemReferralCode(UUID bookingId) {
        referralService.redeemReferralCodeForPaidBooking(requireBooking(bookingId));
    }

    /** Posts the host's earning to the ledger (idempotent per payment). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHostEarning(UUID paymentId) {
        Payment payment = requirePayment(paymentId);
        hostLedgerService.recordEarning(payment.getBooking(), payment);
    }

    /**
     * Records the gift-card redemption against the booking. The balance was already drawn down when
     * the card was reserved at checkout, so this is an audit record only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGiftCardRedemption(UUID paymentId) {
        Payment payment = requirePayment(paymentId);
        if (payment.getGiftCardId() == null) {
            return;
        }
        Booking booking = payment.getBooking();
        giftCardService.recordBookingRedemption(
                payment.getGiftCardId(),
                booking.getId(),
                booking.getLoggedInUser() != null ? booking.getLoggedInUser().getId() : null,
                payment.getGiftCardAmount());
    }

    /** Issues the commission invoice + service-fee receipt (idempotent per booking/invoice type). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateInvoice(UUID paymentId) {
        Payment payment = requirePayment(paymentId);
        invoiceService.generateForConfirmedPayment(payment.getBooking(), payment);
    }

    /** Sends the customer's booking-confirmed notification (idempotent per booking via its dedupe key). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendConfirmationNotification(UUID bookingId) {
        bookingConfirmationNotifier.sendConfirmation(requireBooking(bookingId));
    }

    private Payment requirePayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment " + paymentId + " not found during post-payment finalization"));
    }

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException(
                        "Booking " + bookingId + " not found during post-payment finalization"));
    }
}
