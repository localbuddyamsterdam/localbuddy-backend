package com.localbuddy.payment;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.booking.*;
import com.localbuddy.experience.*;
import com.localbuddy.giftcard.GiftCard;
import com.localbuddy.giftcard.GiftCardRedemptionRepository;
import com.localbuddy.giftcard.GiftCardRepository;
import com.localbuddy.giftcard.GiftCardStatus;
import com.localbuddy.invoice.InvoiceService;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.localprofile.LocalVerificationStatus;
import com.localbuddy.payout.HostLedgerEntry;
import com.localbuddy.payout.HostLedgerEntryRepository;
import com.localbuddy.payout.LedgerEntryType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Proves the post-payment hardening against the real Spring context, a real database and the real
 * transaction boundaries: when a best-effort finalisation step fails <em>after</em> the charge has
 * landed, the payment stays PAID, the booking stays CONFIRMED, the caller gets a success response
 * (no 500), and the other best-effort steps — which run in their own transactions — still commit.
 *
 * <p>The booking is fully covered by a gift card, so it completes with no Stripe charge (the
 * deterministic, key-free finalisation path also used by {@code GiftCardCheckoutIT}). The invoice
 * service is replaced by a mock that throws, standing in for any downstream step that blows up after
 * payment. Because these downstream services are {@code @Transactional}, a naive in-transaction
 * try/catch would still let Spring mark the transaction rollback-only and fail the commit — this
 * test guards against that regression, not just against an un-caught exception.
 *
 * <p>Like the sibling {@code *IT}s this needs the database/JWT env and is kept out of the default
 * {@code mvn test} by its {@code *IT} name. Run explicitly:
 * {@code mvnw test -Dtest=PaidBookingFinalizationIT} with the env set.
 */
@SpringBootTest
class PaidBookingFinalizationIT {

    @Autowired PaymentService paymentService;

    @Autowired PaymentRepository paymentRepository;
    @Autowired GiftCardRepository giftCardRepository;
    @Autowired GiftCardRedemptionRepository redemptionRepository;
    @Autowired HostLedgerEntryRepository ledgerRepository;

    @Autowired UserRepository userRepository;
    @Autowired LocalProfileRepository localProfileRepository;
    @Autowired CityRepository cityRepository;
    @Autowired ExperienceRepository experienceRepository;
    @Autowired AvailabilitySlotRepository slotRepository;
    @Autowired BookingRepository bookingRepository;

    /** Stand-in for "a downstream finalisation step throws after the charge succeeded". */
    @MockitoBean InvoiceService invoiceService;

    @Test
    void invoiceGenerationFailure_doesNotRollBackOrFailThePaidConfirmedBooking() {
        // A booking fully covered by a gift card → no Stripe charge, so finalisation runs inline.
        Fixture f = newBooking(new BigDecimal("100.00"), Instant.now().plus(48, ChronoUnit.HOURS));
        BigDecimal amount = pendingAmount(f);
        GiftCard card = saveActiveGiftCard(amount.add(new BigDecimal("20.00")));

        doThrow(new RuntimeException("invoice generation boom"))
                .when(invoiceService).generateForConfirmedPayment(any(), any());

        // Must NOT throw: a failure here previously propagated as a 500 even though the money landed.
        PaymentCheckoutResponse resp =
                paymentService.createCheckout(f.traveler.getId(), checkoutReq(f, card.getCode()));

        assertEquals(PaymentStatus.PAID, resp.paymentStatus(), "caller still gets a paid response");

        // Core state is committed and durable despite the invoice failure.
        Payment payment = paymentRepository.findByBookingId(f.booking.getId()).orElseThrow();
        assertEquals(PaymentStatus.PAID, payment.getPaymentStatus(), "payment committed as PAID");
        assertEquals(BookingStatus.CONFIRMED, reload(f.booking).getStatus(), "booking committed as CONFIRMED");

        // The invoice step really was exercised (and threw).
        verify(invoiceService).generateForConfirmedPayment(any(), any());

        // The other best-effort steps run in their own transactions, so the invoice failure neither
        // poisoned nor rolled them back: the host-ledger earning and the gift-card redemption persisted.
        List<HostLedgerEntry> ledger = ledgerRepository.findByPaymentId(payment.getId());
        assertTrue(ledger.stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.EARNING),
                "host-ledger earning recorded despite the invoice failure");
        assertFalse(redemptionRepository.findByGiftCardIdOrderByCreatedAtDesc(card.getId()).isEmpty(),
                "gift-card redemption recorded despite the invoice failure");
    }

    // ---------------------------------------------------------------------
    // Stripe stub (this path never charges, but the provider bean must exist without Stripe keys)
    // ---------------------------------------------------------------------

    @TestConfiguration
    static class StubStripeConfig {
        @Bean
        @Primary
        PaymentCheckoutProvider stubCheckoutProvider() {
            return new PaymentCheckoutProvider() {
                @Override
                public PaymentProvider getProvider() {
                    return PaymentProvider.STRIPE;
                }

                @Override
                public PaymentCheckoutResult createCheckout(Payment payment) {
                    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    return new PaymentCheckoutResult(
                            "https://stripe.test/cs_" + id, "cs_test_" + id, "pi_test_" + id, PaymentMethodType.UNKNOWN);
                }

                @Override
                public PaymentCheckoutResult createGiftCardCheckout(UUID giftCardId, BigDecimal amount, String currency, String reference) {
                    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    return new PaymentCheckoutResult(
                            "https://stripe.test/gc_" + id, "cs_gift_" + id, "pi_gift_" + id, PaymentMethodType.UNKNOWN);
                }

                @Override
                public PaymentRefundResult refundPayment(Payment payment, BigDecimal refundAmount, String reason) {
                    return new PaymentRefundResult("re_test", PaymentStatus.REFUNDED, refundAmount);
                }

                @Override
                public void expireCheckout(String providerCheckoutSessionId) {
                }
            };
        }
    }

    // ---------------------------------------------------------------------
    // seeding helpers (mirrors GiftCardCheckoutIT)
    // ---------------------------------------------------------------------

    private record Fixture(User traveler, LocalProfile host, Booking booking) {}

    private CreatePaymentRequest checkoutReq(Fixture f, String giftCardCode) {
        return new CreatePaymentRequest(f.booking.getId(), giftCardCode);
    }

    private BigDecimal pendingAmount(Fixture f) {
        return paymentService.createPendingPayment(
                f.traveler.getId(), new CreatePaymentRequest(f.booking.getId(), null)).amount();
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
        c.setCode("LB-FIN-" + shortTag().toUpperCase());
        c.setInitialAmount(balance);
        c.setBalance(balance);
        c.setCurrency("EUR");
        c.setStatus(GiftCardStatus.ACTIVE);
        return giftCardRepository.save(c);
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    private static String shortTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User saveUser(String email, UserRole role) {
        User u = new User();
        u.setFullName("Test " + role);
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
        b.setBookingReference("LB-FIN-" + tag.toUpperCase());
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
}
