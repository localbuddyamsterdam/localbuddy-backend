package com.localbuddy.finance;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.booking.*;
import com.localbuddy.experience.*;
import com.localbuddy.invoice.Invoice;
import com.localbuddy.invoice.InvoiceRepository;
import com.localbuddy.invoice.InvoiceService;
import com.localbuddy.invoice.InvoiceType;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.localprofile.LocalVerificationStatus;
import com.localbuddy.payment.*;
import com.localbuddy.payout.*;
import com.localbuddy.pricing.PricingEngine;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: a real €100 booking flows through engine -> payment snapshot ->
 * ledger earning -> invoices (PDF) -> payout -> settlement -> clawback, against
 * the real Spring context and a database.
 */
@SpringBootTest
class FinancialPipelineIT {

    @Autowired UserRepository userRepository;
    @Autowired LocalProfileRepository localProfileRepository;
    @Autowired CityRepository cityRepository;
    @Autowired ExperienceRepository experienceRepository;
    @Autowired AvailabilitySlotRepository slotRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired HostLedgerEntryRepository ledgerRepository;
    @Autowired InvoiceRepository invoiceRepository;

    @Autowired PricingEngine pricingEngine;
    @Autowired HostLedgerService ledgerService;
    @Autowired InvoiceService invoiceService;
    @Autowired HostPayoutService hostPayoutService;

    @Test
    void fullPipeline_for_a_100eur_NL_registered_host() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        User hostUser = saveUser("host-" + tag + "@test.com", UserRole.LOCAL);
        LocalProfile host = saveHost(hostUser);            // NL VAT-registered
        User traveler = saveUser("trav-" + tag + "@test.com", UserRole.TRAVELER);
        City city = saveCity(tag);
        Experience exp = saveExperience(host, city, tag);  // price 100 EUR, gross
        AvailabilitySlot slot = saveSlot(exp, host);       // ended 5 days ago (so earning is payable)
        Booking booking = saveBooking(traveler, host, exp, slot, tag); // total 100

        // 1) Engine snapshots the breakdown onto the payment.
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(PaymentProvider.STRIPE);
        payment.setPaymentMethodType(PaymentMethodType.UNKNOWN);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        pricingEngine.applyTo(payment, booking);
        payment = paymentRepository.save(payment);

        assertEq("103.03", payment.getAmount(), "customer total");
        assertEq("20.00", payment.getCommissionAmount(), "commission");
        assertEq("4.20", payment.getCommissionVatAmount(), "commission VAT");
        assertEq("17.36", payment.getExperienceVatAmount(), "experience VAT");
        assertEq("2.50", payment.getServiceFeeAmount(), "service fee");
        assertEq("0.53", payment.getServiceFeeVatAmount(), "service fee VAT");
        assertEq("75.80", payment.getHostPayoutAmount(), "host payout");
        assertEquals("STANDARD", payment.getCommissionVatTreatment());

        // 2) On confirm: ledger earning posts and (slot ended) is payable.
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment = paymentRepository.save(payment);
        ledgerService.recordEarning(booking, payment);

        List<HostLedgerEntry> available = ledgerRepository
                .findByLocalProfileIdAndStatus(host.getId(), LedgerEntryStatus.AVAILABLE);
        assertEquals(1, available.size(), "one available earning");
        assertEq("75.80", available.get(0).getAmount(), "ledger earning amount");

        // 3) Invoices: commission to host + receipt to customer, both as real PDFs.
        invoiceService.generateForConfirmedPayment(booking, payment);
        List<Invoice> invoices = invoiceRepository.findByBookingIdOrderByIssuedAtDesc(booking.getId());
        Invoice commission = invoices.stream().filter(i -> i.getInvoiceType() == InvoiceType.COMMISSION).findFirst().orElseThrow();
        Invoice receipt = invoices.stream().filter(i -> i.getInvoiceType() == InvoiceType.SERVICE_FEE_RECEIPT).findFirst().orElseThrow();
        assertEq("24.20", commission.getTotalAmount(), "commission invoice total");
        assertEq("3.03", receipt.getTotalAmount(), "service-fee receipt total");

        byte[] pdf = invoiceService.renderPdf(commission.getId(), null, true);
        assertTrue(pdf.length > 500 && "%PDF".equals(new String(pdf, 0, 4, StandardCharsets.US_ASCII)), "commission invoice is a PDF");

        // 4) Payout batches the payable earning; manual disbursement settles it + statement.
        PayoutResponse payout = hostPayoutService.createPayoutForHost(host.getId());
        assertEq("75.80", payout.amount(), "payout amount");
        hostPayoutService.markPayoutPaid(payout.id(), "integration test");

        HostLedgerEntry settled = ledgerRepository.findById(available.get(0).getId()).orElseThrow();
        assertEquals(LedgerEntryStatus.PAID, settled.getStatus(), "earning settled after payout");
        assertEquals(1, invoiceRepository.findByPayoutIdOrderByIssuedAtDesc(payout.id()).size(), "payout statement issued");

        // 5) Refund after payout -> negative clawback against future earnings.
        ledgerService.reverseForPayment(payment.getId(), "refund after payout");
        boolean clawback = ledgerRepository.findByPaymentId(payment.getId()).stream()
                .anyMatch(e -> e.getEntryType() == LedgerEntryType.REVERSAL
                        && e.getAmount().compareTo(new BigDecimal("-75.80")) == 0);
        assertTrue(clawback, "clawback entry of -75.80 created");
    }

    // ---- seeding helpers ----

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

    private AvailabilitySlot saveSlot(Experience exp, LocalProfile host) {
        AvailabilitySlot s = new AvailabilitySlot();
        s.setExperience(exp);
        s.setLocalProfile(host);
        s.setStartTime(Instant.now().minus(5, ChronoUnit.DAYS));
        s.setEndTime(Instant.now().minus(5, ChronoUnit.DAYS).plus(90, ChronoUnit.MINUTES));
        s.setCapacity(6);
        s.setBookedCount(1);
        s.setStatus(AvailabilityStatus.AVAILABLE);
        return slotRepository.save(s);
    }

    private Booking saveBooking(User traveler, LocalProfile host, Experience exp, AvailabilitySlot slot, String tag) {
        Booking b = new Booking();
        b.setBookingReference("LB-" + tag.toUpperCase());
        b.setTravelerUser(traveler);
        b.setBookingSource(BookingSource.LOGGED_IN_USER);
        b.setLocalProfile(host);
        b.setExperience(exp);
        b.setAvailabilitySlot(slot);
        b.setGuestsCount(1);
        b.setSeatsBlocked(1);
        b.setStatus(BookingStatus.PENDING_PAYMENT);
        b.setPricePerGuest(new BigDecimal("100.00"));
        b.setTotalAmount(new BigDecimal("100.00"));
        b.setCurrency("EUR");
        b.setRequestedAt(Instant.now());
        return bookingRepository.save(b);
    }

    private static void assertEq(String expected, BigDecimal actual, String what) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), what + " expected=" + expected + " actual=" + actual);
    }
}
