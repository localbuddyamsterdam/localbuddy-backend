package com.localbuddy.giftcard;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.booking.*;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.experience.*;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.localprofile.LocalVerificationStatus;
import com.localbuddy.payment.*;
import com.localbuddy.payout.HostLedgerService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of the three gift-card money paths against the real Spring
 * context, a real Postgres (Flyway-migrated through V24) and the real transaction
 * boundaries / row locks. The single external boundary — Stripe — is replaced by a
 * recording stub ({@link RecordingCheckoutProvider}) that captures the exact amounts
 * the application asks Stripe to charge/refund and mimics Stripe's "cannot refund
 * more than captured" rule, so the application logic is exercised deterministically
 * without any Stripe keys. The real Stripe API contract is validated separately by
 * the runbook in docs/giftcard-stripe-validation.md.
 *
 * <p>Not run by the default Maven lifecycle (no Failsafe config + the {@code *IT}
 * name keep it out of {@code mvn test}). Run explicitly:
 * {@code mvnw test -Dtest=GiftCardCheckoutIT} with the DB/JWT/Stripe-dummy env set.
 */
@SpringBootTest
class GiftCardCheckoutIT {

    @Autowired PaymentService paymentService;
    @Autowired GiftCardService giftCardService;
    @Autowired HostLedgerService hostLedgerService;

    @Autowired PaymentRepository paymentRepository;
    @Autowired GiftCardRepository giftCardRepository;
    @Autowired GiftCardRedemptionRepository redemptionRepository;
    @Autowired PaymentWebhookEventRepository webhookEventRepository;

    @Autowired UserRepository userRepository;
    @Autowired LocalProfileRepository localProfileRepository;
    @Autowired CityRepository cityRepository;
    @Autowired ExperienceRepository experienceRepository;
    @Autowired AvailabilitySlotRepository slotRepository;
    @Autowired BookingRepository bookingRepository;

    @Autowired RecordingCheckoutProvider stripe;

    @BeforeEach
    void resetStub() {
        stripe.reset();
    }

    // ---------------------------------------------------------------------
    // FLOW 1 — redeem at checkout
    // ---------------------------------------------------------------------

    /** Gift card covers the whole payable amount → no Stripe charge, booking confirmed, redemption recorded once. */
    @Test
    void fullCoverage_completesWithoutStripe_andRecordsRedemptionOnce() {
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(48, ChronoUnit.HOURS));
        BigDecimal amount = pendingAmount(f);                       // ~103.03 incl. service fee
        GiftCard card = saveActiveGiftCard(amount.add(new BigDecimal("20.00")));

        PaymentCheckoutResponse resp =
                paymentService.createCheckout(f.traveler.getId(), checkoutReq(f, card.getCode()));

        assertEquals(PaymentStatus.PAID, resp.paymentStatus(), "fully gift-covered booking is paid immediately");
        assertTrue(stripe.bookingCharges.isEmpty(), "Stripe must not be called when the gift card covers everything");

        Payment payment = paymentRepository.findByBookingId(f.booking.getId()).orElseThrow();
        assertEquals(PaymentStatus.PAID, payment.getPaymentStatus());
        assertEq(amount, payment.getGiftCardAmount(), "gift card amount equals the full payable amount");
        assertEquals(BookingStatus.CONFIRMED, reload(f.booking).getStatus(), "booking confirmed");

        GiftCard after = giftCardRepository.findById(card.getId()).orElseThrow();
        assertEq("20.00", after.getBalance(), "remaining gift balance");
        assertEquals(GiftCardStatus.ACTIVE, after.getStatus(), "card still active with leftover balance");

        List<GiftCardRedemption> redemptions = redemptionRepository.findByGiftCardIdOrderByCreatedAtDesc(card.getId());
        assertEquals(1, redemptions.size(), "exactly one redemption recorded");
        assertEq(amount, redemptions.get(0).getAmount(), "redemption amount");
    }

    /** Gift card covers part → Stripe charged exactly (amount − gift), balance depleted once, no redemption until finalize. */
    @Test
    void partialCoverage_chargesStripeForRemainder_andDecrementsBalanceOnce() {
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(48, ChronoUnit.HOURS));
        BigDecimal amount = pendingAmount(f);
        GiftCard card = saveActiveGiftCard(new BigDecimal("30.00"));

        PaymentCheckoutResponse resp =
                paymentService.createCheckout(f.traveler.getId(), checkoutReq(f, card.getCode()));

        assertEquals(PaymentStatus.PROCESSING, resp.paymentStatus(), "partially covered → goes to Stripe");
        assertEquals(1, stripe.bookingCharges.size(), "Stripe charged once");
        assertEq(amount.subtract(new BigDecimal("30.00")), stripe.bookingCharges.get(0),
                "Stripe charge is the remainder after the gift card");

        Payment payment = paymentRepository.findByBookingId(f.booking.getId()).orElseThrow();
        assertEq("30.00", payment.getGiftCardAmount(), "gift card amount on payment");
        assertNotNull(payment.getProviderCheckoutSessionId(), "checkout session attached");

        GiftCard after = giftCardRepository.findById(card.getId()).orElseThrow();
        assertEq("0.00", after.getBalance(), "balance decremented exactly once at reserve");
        assertEquals(GiftCardStatus.DEPLETED, after.getStatus(), "card depleted");

        assertTrue(redemptionRepository.findByGiftCardIdOrderByCreatedAtDesc(card.getId()).isEmpty(),
                "redemption is only recorded after the payment is finalized, not at reserve");
    }

    /** Gift card leaves a sub-€0.50 remainder Stripe would reject → platform absorbs it, booking completes (fix for finding #1). */
    @Test
    void subMinimumRemainder_isAbsorbed_notSentToStripe() {
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(48, ChronoUnit.HOURS));
        BigDecimal amount = pendingAmount(f);
        GiftCard card = saveActiveGiftCard(amount.subtract(new BigDecimal("0.30")));   // leaves €0.30 < €0.50

        PaymentCheckoutResponse resp =
                paymentService.createCheckout(f.traveler.getId(), checkoutReq(f, card.getCode()));

        assertEquals(PaymentStatus.PAID, resp.paymentStatus(), "sub-minimum remainder is absorbed → paid immediately");
        assertTrue(stripe.bookingCharges.isEmpty(), "a €0.30 remainder must never be sent to Stripe (it would be rejected)");
        assertEquals(BookingStatus.CONFIRMED, reload(f.booking).getStatus(), "booking confirmed despite the unpaid few cents");

        GiftCard after = giftCardRepository.findById(card.getId()).orElseThrow();
        assertEq("0.00", after.getBalance(), "card fully drawn down");
        assertEquals(GiftCardStatus.DEPLETED, after.getStatus());
    }

    // ---------------------------------------------------------------------
    // FLOW 2 — refund to card
    // ---------------------------------------------------------------------

    /** 100% refund of a gift+cash booking: gift share returns to the card, cash share goes to Stripe, totals reconcile. */
    @Test
    void refund_splitsBetweenCardAndStripe_andReconciles() {
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(48, ChronoUnit.HOURS)); // >24h → 100% policy
        Payment payment = paidBookingWithGift(f, new BigDecimal("40.00"));
        BigDecimal capturedCash = payment.getAmount().subtract(new BigDecimal("40.00"));

        paymentService.handleBookingCancellationPayment(
                f.booking, BookingCancellationActor.LOGGED_IN_USER, "customer cancelled");

        GiftCard card = giftCardRepository.findById(payment.getGiftCardId()).orElseThrow();
        assertEq("40.00", card.getBalance(), "full gift share returned to the card");
        assertEquals(GiftCardStatus.ACTIVE, card.getStatus(), "depleted card reactivated by the return");

        assertEquals(1, stripe.refunds.size(), "exactly one Stripe cash refund");
        BigDecimal cashRefund = stripe.refunds.get(0);
        assertEq("60.00", cashRefund, "cash refund = 100% of booking total (100) minus the gift share (40)");
        assertTrue(cashRefund.compareTo(capturedCash) <= 0, "cash refund never exceeds the cash Stripe actually captured");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEq("100.00", after.getRefundedAmount(), "gift (40) + cash (60) reconcile to the booking total");
        assertNotNull(after.getRefundedAt(), "refund timestamp set");
        // Documents a real quirk: the Stripe leg (60) < payment total (~103.03) so the provider labels it
        // PARTIALLY_REFUNDED even though the booking was refunded in full. Flagged as a low-severity finding.
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, after.getPaymentStatus());
    }

    /** Late cancellation → 0% policy: host earning intact, nothing returns to the card, no Stripe refund. */
    @Test
    void zeroRefundPolicy_returnsNothing() {
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(1, ChronoUnit.HOURS)); // <24h → 0% policy
        Payment payment = paidBookingWithGift(f, new BigDecimal("40.00"));

        paymentService.handleBookingCancellationPayment(
                f.booking, BookingCancellationActor.LOGGED_IN_USER, "late cancellation");

        GiftCard card = giftCardRepository.findById(payment.getGiftCardId()).orElseThrow();
        assertEq("0.00", card.getBalance(), "no refund → gift balance unchanged");
        assertEquals(GiftCardStatus.DEPLETED, card.getStatus());
        assertTrue(stripe.refunds.isEmpty(), "no Stripe refund on a 0% policy");

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.PAID, after.getPaymentStatus(), "payment stays PAID when the refund is 0");
        assertEquals("late cancellation", after.getRefundReason());
    }

    // ---------------------------------------------------------------------
    // FLOW 3 — purchase via Stripe
    // ---------------------------------------------------------------------

    /** Purchase mints PENDING_PAYMENT (unspendable); only the webhook's activate flips it ACTIVE, idempotently. */
    @Test
    void purchase_mintsPending_andOnlyWebhookActivates_idempotently() {
        User buyer = saveUser("buyer-" + shortTag() + "@test.com", UserRole.LOGGED_IN_USER);

        GiftCardPurchaseResponse resp = giftCardService.purchase(
                buyer.getId(), new PurchaseGiftCardRequest(new BigDecimal("50.00"), "EUR", null, null, null));

        assertEquals(GiftCardStatus.PENDING_PAYMENT, resp.status(), "card is not active before payment settles");
        assertNotNull(resp.checkoutUrl(), "purchase returns a Stripe checkout url");
        assertEquals(1, stripe.giftCardCharges.size(), "Stripe asked to charge for the purchase");
        assertEq("50.00", stripe.giftCardCharges.get(0), "purchase charge = full gift value");

        // Unspendable before settlement.
        assertThrows(BadRequestException.class,
                () -> giftCardService.reserveForCheckout(resp.code(), new BigDecimal("10.00")),
                "a PENDING_PAYMENT card cannot be redeemed");

        // Webhook activation — idempotent across retries.
        giftCardService.activatePurchasedCard(resp.giftCardId());
        assertEquals(GiftCardStatus.ACTIVE, giftCardRepository.findById(resp.giftCardId()).orElseThrow().getStatus());
        giftCardService.activatePurchasedCard(resp.giftCardId());   // duplicate webhook delivery
        assertEquals(GiftCardStatus.ACTIVE, giftCardRepository.findById(resp.giftCardId()).orElseThrow().getStatus(),
                "second activation is a no-op, not an error");

        // Now usable.
        GiftCardApplication app = giftCardService.reserveForCheckout(resp.code(), new BigDecimal("10.00"));
        assertEq("10.00", app.amount(), "redeemable once active");
        assertEq("40.00", giftCardRepository.findById(resp.giftCardId()).orElseThrow().getBalance(), "balance after redeem");
    }

    /** The DB unique constraint on (provider, provider_event_id) is what makes webhook retries idempotent. */
    @Test
    void duplicateWebhookEvent_isRejectedByUniqueConstraint() {
        String eventId = "evt_dup_" + shortTag();

        webhookEventRepository.saveAndFlush(webhookEvent(eventId));

        assertThrows(DataIntegrityViolationException.class,
                () -> webhookEventRepository.saveAndFlush(webhookEvent(eventId)),
                "a second event row with the same Stripe event id must be rejected");
    }

    // ---------------------------------------------------------------------
    // Stripe stub
    // ---------------------------------------------------------------------

    @TestConfiguration
    static class StubStripeConfig {
        @Bean
        @Primary
        RecordingCheckoutProvider recordingCheckoutProvider() {
            return new RecordingCheckoutProvider();
        }
    }

    /** Records what the app asks Stripe to do and mimics Stripe's refund-cap rule; never calls Stripe. */
    static class RecordingCheckoutProvider implements PaymentCheckoutProvider {
        final List<BigDecimal> bookingCharges = new ArrayList<>();
        final List<BigDecimal> giftCardCharges = new ArrayList<>();
        final List<BigDecimal> refunds = new ArrayList<>();

        void reset() {
            bookingCharges.clear();
            giftCardCharges.clear();
            refunds.clear();
        }

        // Globally-unique ids: payment session/intent ids are uniquely indexed and this DB is not
        // wiped between runs, so a per-test counter would collide with earlier runs' rows.
        private static String uid() {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        @Override
        public PaymentProvider getProvider() {
            return PaymentProvider.STRIPE;
        }

        @Override
        public PaymentCheckoutResult createCheckout(Payment payment) {
            BigDecimal gift = payment.getGiftCardAmount() != null ? payment.getGiftCardAmount() : BigDecimal.ZERO;
            bookingCharges.add(payment.getAmount().subtract(gift));
            String id = uid();
            return new PaymentCheckoutResult(
                    "https://stripe.test/checkout/cs_" + id, "cs_test_" + id, "pi_test_" + id, PaymentMethodType.UNKNOWN);
        }

        @Override
        public PaymentCheckoutResult createGiftCardCheckout(UUID giftCardId, BigDecimal amount, String currency, String reference) {
            giftCardCharges.add(amount);
            String id = uid();
            return new PaymentCheckoutResult(
                    "https://stripe.test/checkout/gc_" + id, "cs_gift_" + id, "pi_gift_" + id, PaymentMethodType.UNKNOWN);
        }

        @Override
        public PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason) {
            BigDecimal gift = payment.getGiftCardAmount() != null ? payment.getGiftCardAmount() : BigDecimal.ZERO;
            BigDecimal capturedCash = payment.getAmount().subtract(gift);
            if (refundAmount.compareTo(capturedCash) > 0) {
                // This is exactly how real Stripe behaves — refunds cannot exceed the captured charge.
                throw new IllegalStateException("refund " + refundAmount + " exceeds captured cash " + capturedCash);
            }
            refunds.add(refundAmount);
            PaymentStatus status = refundAmount.compareTo(payment.getAmount()) < 0
                    ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.REFUNDED;
            return new PaymentRefundResult("re_test_" + uid(), status, refundAmount);
        }

        @Override
        public void expireCheckout(String providerCheckoutSessionId) {
            // no-op
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private record Fixture(User traveler, LocalProfile host, Booking booking) {}

    private CreatePaymentRequest checkoutReq(Fixture f, String giftCardCode) {
        return new CreatePaymentRequest(f.booking.getId(), giftCardCode);
    }

    private BigDecimal pendingAmount(Fixture f) {
        return paymentService.createPendingPayment(
                f.traveler.getId(), new CreatePaymentRequest(f.booking.getId(), null)).amount();
    }

    /** Builds a PAID payment whose gift card was reserved against the booking, ready for a refund test. */
    private Payment paidBookingWithGift(Fixture f, BigDecimal giftBalance) {
        BigDecimal amount = pendingAmount(f);
        GiftCard card = saveActiveGiftCard(giftBalance);
        GiftCardApplication app = giftCardService.reserveForCheckout(card.getCode(), amount);

        Payment payment = paymentRepository.findByBookingId(f.booking.getId()).orElseThrow();
        payment.setGiftCardId(app.giftCardId());
        payment.setGiftCardAmount(app.amount());
        payment.setProviderCheckoutSessionId("cs_refund_" + shortTag());
        payment.setProviderPaymentIntentId("pi_refund_" + shortTag());
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setPaidAt(Instant.now());
        payment = paymentRepository.save(payment);

        hostLedgerService.recordEarning(f.booking, payment);
        f.booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(f.booking);
        return payment;
    }

    private Fixture newBooking(BigDecimal total, Instant slotStart) {
        String tag = shortTag();
        User hostUser = saveUser("host-" + tag + "@test.com", UserRole.LOCAL);
        LocalProfile host = saveHost(hostUser);
        User traveler = saveUser("trav-" + tag + "@test.com", UserRole.LOGGED_IN_USER);
        City city = saveCity(tag);
        Experience exp = saveExperience(host, city, tag);
        AvailabilitySlot slot = saveSlot(exp, host, slotStart);
        Booking booking = saveBooking(traveler, host, exp, slot, tag, total);
        return new Fixture(traveler, host, booking);
    }

    private GiftCard saveActiveGiftCard(BigDecimal balance) {
        GiftCard c = new GiftCard();
        c.setCode("LB-IT-" + shortTag().toUpperCase());
        c.setInitialAmount(balance);
        c.setBalance(balance);
        c.setCurrency("EUR");
        c.setStatus(GiftCardStatus.ACTIVE);
        return giftCardRepository.save(c);
    }

    private PaymentWebhookEvent webhookEvent(String eventId) {
        PaymentWebhookEvent e = new PaymentWebhookEvent();
        e.setProvider(PaymentProvider.STRIPE);
        e.setProviderEventId(eventId);
        e.setEventType("checkout.session.completed");
        e.setProcessed(true);
        return e;
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    private static String shortTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User saveUser(String email, UserRole role) {
        User u = new User();
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setEmail(email);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        return userRepository.save(u);
    }

    private LocalProfile saveHost(User user) {
        LocalProfile p = new LocalProfile();
        p.setUser(user);
        p.setDisplayName("Test Host");
        p.setBio("bio");
        p.setPhoneNumber("+31600000000");
        p.setHostCity("Amsterdam");
        p.setZipCode("1011AA");
        p.setCountry("Netherlands");
        p.setMotivation("m");
        p.setExperienceInfo("e");
        p.setProfilePhotoUrl("http://x/p.jpg");
        p.setLegalFirstName("Jane");
        p.setLegalLastName("Host");
        p.setPreferredName("Jane");
        p.setCurrentAddress("Damrak 1, Amsterdam");
        p.setApprovalStatus(LocalApprovalStatus.APPROVED);
        p.setVerificationStatus(LocalVerificationStatus.NOT_STARTED);
        p.setVatRegistered(true);
        p.setTaxCountry("NL");
        return localProfileRepository.save(p);
    }

    private City saveCity(String tag) {
        City c = new City();
        c.setName("Amsterdam-" + tag);
        c.setSlug("amsterdam-" + tag);
        c.setCountry("Netherlands");
        c.setActive(true);
        c.setDisplayOrder(0);
        return cityRepository.save(c);
    }

    private Experience saveExperience(LocalProfile host, City city, String tag) {
        Experience e = new Experience();
        e.setLocalProfile(host);
        e.setCity(city);
        e.setTitle("Canal tour " + tag);
        e.setSlug("canal-tour-" + tag);
        e.setDescription("A lovely tour.");
        e.setDurationMinutes(90);
        e.setPriceAmount(new BigDecimal("100.00"));
        e.setCurrency("EUR");
        e.setMaxGuests(6);
        e.setMinimumAge(0);
        e.setBookingMode(BookingMode.SHARED);
        e.setPriceInputMode(PriceInputMode.GROSS);
        e.setStatus(ExperienceStatus.APPROVED);
        return experienceRepository.save(e);
    }

    private AvailabilitySlot saveSlot(Experience exp, LocalProfile host, Instant start) {
        AvailabilitySlot s = new AvailabilitySlot();
        s.setExperience(exp);
        s.setLocalProfile(host);
        s.setStartTime(start);
        s.setEndTime(start.plus(90, ChronoUnit.MINUTES));
        s.setCapacity(6);
        s.setBookedCount(1);
        s.setStatus(AvailabilityStatus.AVAILABLE);
        return slotRepository.save(s);
    }

    private Booking saveBooking(User traveler, LocalProfile host, Experience exp, AvailabilitySlot slot, String tag, BigDecimal total) {
        Booking b = new Booking();
        b.setBookingReference("LB-GC-" + tag.toUpperCase());
        b.setLoggedInUser(traveler);
        b.setBookingSource(BookingSource.LOGGED_IN_USER);
        b.setLocalProfile(host);
        b.setExperience(exp);
        b.setAvailabilitySlot(slot);
        b.setGuestsCount(1);
        b.setSeatsBlocked(1);
        b.setStatus(BookingStatus.PENDING_PAYMENT);
        b.setPricePerGuest(total);
        b.setTotalAmount(total);
        b.setCurrency("EUR");
        b.setRequestedAt(Instant.now());
        return bookingRepository.save(b);
    }

    private static void assertEq(String expected, BigDecimal actual, String what) {
        assertEq(new BigDecimal(expected), actual, what);
    }

    private static void assertEq(BigDecimal expected, BigDecimal actual, String what) {
        assertEquals(0, expected.compareTo(actual), what + " expected=" + expected + " actual=" + actual);
    }
}
