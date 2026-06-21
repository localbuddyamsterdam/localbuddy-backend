package com.localbuddy.invoice;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.payment.Payment;
import com.localbuddy.payout.HostLedgerEntry;
import com.localbuddy.payout.HostLedgerEntryRepository;
import com.localbuddy.payout.Payout;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues VAT invoices/receipts and payout statements. Invoice numbers and the
 * issuer's company/BTW details are snapshotted so issued documents are immutable.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final InvoicePdfService pdfService;
    private final CompanySettingsRepository companySettingsRepository;
    private final NotificationService notificationService;
    private final HostLedgerEntryRepository ledgerEntryRepository;
    private final LocalProfileRepository localProfileRepository;
    private final BookingRepository bookingRepository;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoiceLineRepository invoiceLineRepository,
                          InvoiceNumberService invoiceNumberService,
                          InvoicePdfService pdfService,
                          CompanySettingsRepository companySettingsRepository,
                          NotificationService notificationService,
                          HostLedgerEntryRepository ledgerEntryRepository,
                          LocalProfileRepository localProfileRepository,
                          BookingRepository bookingRepository) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.pdfService = pdfService;
        this.companySettingsRepository = companySettingsRepository;
        this.notificationService = notificationService;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.localProfileRepository = localProfileRepository;
        this.bookingRepository = bookingRepository;
    }

    /** Issues the commission invoice (to host) and service-fee receipt (to customer). */
    @Transactional
    public void generateForConfirmedPayment(Booking booking, Payment payment) {
        if (booking == null || payment == null) {
            return;
        }
        generateCommissionInvoice(booking, payment);
        generateServiceFeeReceipt(booking, payment);
    }

    private void generateCommissionInvoice(Booking booking, Payment payment) {
        if (invoiceRepository.existsByBookingIdAndInvoiceType(booking.getId(), InvoiceType.COMMISSION)) {
            return;
        }
        LocalProfile host = booking.getLocalProfile();
        BigDecimal commission = nz(payment.getCommissionAmount());
        BigDecimal commissionVat = nz(payment.getCommissionVatAmount());
        BigDecimal commissionVatRate = nz(payment.getCommissionVatRate());

        Invoice invoice = newInvoice(InvoiceType.COMMISSION, payment.getCurrency());
        invoice.setRecipientType(RecipientType.HOST);
        invoice.setRecipientName(hostName(host));
        invoice.setRecipientEmail(host != null && host.getUser() != null ? host.getUser().getEmail() : null);
        invoice.setRecipientVatNumber(host != null ? host.getVatNumber() : null);
        invoice.setRecipientAddress(host != null ? host.getCurrentAddress() : null);
        invoice.setLocalProfileId(host != null ? host.getId() : null);
        invoice.setBookingId(booking.getId());
        invoice.setPaymentId(payment.getId());
        invoice.setSubtotalAmount(commission);
        invoice.setVatAmount(commissionVat);
        invoice.setTotalAmount(commission.add(commissionVat));
        invoice.setVatNote(vatNoteFor(payment.getCommissionVatTreatment()));
        Invoice saved = invoiceRepository.save(invoice);

        saveLine(saved.getId(), "Platform commission for booking " + booking.getBookingReference(),
                commission, commissionVatRate, commissionVat, 0);

        notifyUser(host != null && host.getUser() != null ? host.getUser().getId() : null,
                host, booking, saved, "Your commission invoice " + saved.getInvoiceNumber() + " is available.");
    }

    private void generateServiceFeeReceipt(Booking booking, Payment payment) {
        BigDecimal serviceFee = nz(payment.getServiceFeeAmount());
        if (serviceFee.signum() <= 0
                || invoiceRepository.existsByBookingIdAndInvoiceType(booking.getId(), InvoiceType.SERVICE_FEE_RECEIPT)) {
            return;
        }
        BigDecimal serviceFeeVat = nz(payment.getServiceFeeVatAmount());
        BigDecimal serviceFeeRate = serviceFee.signum() > 0
                ? serviceFeeVat.divide(serviceFee, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Invoice invoice = newInvoice(InvoiceType.SERVICE_FEE_RECEIPT, payment.getCurrency());
        invoice.setRecipientType(RecipientType.CUSTOMER);
        invoice.setRecipientName(customerName(booking));
        invoice.setRecipientEmail(customerEmail(booking));
        invoice.setBookingId(booking.getId());
        invoice.setPaymentId(payment.getId());
        invoice.setSubtotalAmount(serviceFee);
        invoice.setVatAmount(serviceFeeVat);
        invoice.setTotalAmount(serviceFee.add(serviceFeeVat));
        Invoice saved = invoiceRepository.save(invoice);

        saveLine(saved.getId(), "LocalBuddy service fee for booking " + booking.getBookingReference(),
                serviceFee, serviceFeeRate, serviceFeeVat, 0);

        notifyUser(booking.getTravelerUser() != null ? booking.getTravelerUser().getId() : null,
                booking.getLocalProfile(), booking, saved,
                "Your service-fee receipt " + saved.getInvoiceNumber() + " is available.");
    }

    /** Issues a payout statement (to host) listing the earnings settled in a payout. */
    @Transactional
    public void generatePayoutStatement(Payout payout) {
        if (payout == null
                || invoiceRepository.existsByPayoutIdAndInvoiceType(payout.getId(), InvoiceType.PAYOUT_STATEMENT)) {
            return;
        }
        LocalProfile host = payout.getLocalProfile();
        List<HostLedgerEntry> entries = ledgerEntryRepository.findByPayoutId(payout.getId());

        Invoice invoice = newInvoice(InvoiceType.PAYOUT_STATEMENT, payout.getCurrency());
        invoice.setRecipientType(RecipientType.HOST);
        invoice.setRecipientName(hostName(host));
        invoice.setRecipientEmail(host != null && host.getUser() != null ? host.getUser().getEmail() : null);
        invoice.setRecipientAddress(host != null ? host.getCurrentAddress() : null);
        invoice.setLocalProfileId(host != null ? host.getId() : null);
        invoice.setPayoutId(payout.getId());
        invoice.setSubtotalAmount(payout.getAmount());
        invoice.setVatAmount(BigDecimal.ZERO);
        invoice.setTotalAmount(payout.getAmount());
        invoice.setVatNote("Payout remittance; per-booking commission/VAT detail is on the commission invoices.");
        Invoice saved = invoiceRepository.save(invoice);

        int order = 0;
        for (HostLedgerEntry entry : entries) {
            String desc = entry.getDescription() != null ? entry.getDescription() : "Earning";
            saveLine(saved.getId(), desc, nz(entry.getAmount()), BigDecimal.ZERO, BigDecimal.ZERO, order++);
        }

        notifyUser(host != null && host.getUser() != null ? host.getUser().getId() : null,
                host, null, saved, "Your payout statement " + saved.getInvoiceNumber() + " is available.");
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> getMyInvoices(UUID userId) {
        Map<UUID, Invoice> byId = new LinkedHashMap<>();

        localProfileRepository.findByUserId(userId).ifPresent(lp ->
                invoiceRepository.findByLocalProfileIdOrderByIssuedAtDesc(lp.getId())
                        .forEach(i -> byId.putIfAbsent(i.getId(), i)));

        List<UUID> bookingIds = bookingRepository.findByTravelerUserIdOrderByRequestedAtDesc(userId)
                .stream().map(Booking::getId).toList();
        if (!bookingIds.isEmpty()) {
            invoiceRepository.findByBookingIdInOrderByIssuedAtDesc(bookingIds)
                    .forEach(i -> byId.putIfAbsent(i.getId(), i));
        }

        return byId.values().stream()
                .sorted(Comparator.comparing(Invoice::getIssuedAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> getAllInvoices() {
        return invoiceRepository.findAllByOrderByIssuedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID invoiceId, UUID requesterUserId, boolean isAdmin) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        if (!isAdmin && !canAccess(invoice, requesterUserId)) {
            throw new ResourceNotFoundException("Invoice not found");
        }
        List<InvoiceLine> lines = invoiceLineRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId);
        return pdfService.render(invoice, lines);
    }

    private boolean canAccess(Invoice invoice, UUID userId) {
        LocalProfile host = localProfileRepository.findByUserId(userId).orElse(null);
        if (host != null && host.getId().equals(invoice.getLocalProfileId())) {
            return true;
        }
        if (invoice.getBookingId() != null) {
            Booking booking = bookingRepository.findById(invoice.getBookingId()).orElse(null);
            return booking != null && booking.getTravelerUser() != null
                    && booking.getTravelerUser().getId().equals(userId);
        }
        return false;
    }

    // ---- helpers ----

    private Invoice newInvoice(InvoiceType type, String currency) {
        CompanySettings company = companySettingsRepository.findFirstByActiveTrueOrderByUpdatedAtDesc().orElse(null);
        Invoice invoice = new Invoice();
        invoice.setInvoiceType(type);
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setIssuerName(company != null ? company.getLegalName() : "LocalBuddy");
        invoice.setIssuerVatNumber(company != null ? company.getVatNumber() : null);
        invoice.setIssuerAddress(company != null ? companyAddress(company) : null);
        invoice.setIssuerCountry(company != null ? company.getCountry() : "NL");
        invoice.setCurrency(currency != null ? currency : "EUR");
        String prefix = company != null && company.getInvoiceNumberPrefix() != null
                ? company.getInvoiceNumberPrefix() : "LB";
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        invoice.setInvoiceNumber(invoiceNumberService.next(prefix, year));
        return invoice;
    }

    private void saveLine(UUID invoiceId, String description, BigDecimal net, BigDecimal vatRate,
                          BigDecimal vatAmount, int sortOrder) {
        InvoiceLine line = new InvoiceLine();
        line.setInvoiceId(invoiceId);
        line.setDescription(description);
        line.setQuantity(BigDecimal.ONE);
        line.setUnitNetAmount(net);
        line.setNetAmount(net);
        line.setVatRate(vatRate);
        line.setVatAmount(vatAmount);
        line.setTotalAmount(net.add(vatAmount));
        line.setSortOrder(sortOrder);
        invoiceLineRepository.save(line);
    }

    private void notifyUser(UUID userId, LocalProfile host, Booking booking, Invoice invoice, String message) {
        String dedupe = "INVOICE:" + invoice.getId();
        if (userId != null) {
            com.localbuddy.user.User user = null;
            if (host != null && host.getUser() != null && host.getUser().getId().equals(userId)) {
                user = host.getUser();
            } else if (booking != null && booking.getTravelerUser() != null
                    && booking.getTravelerUser().getId().equals(userId)) {
                user = booking.getTravelerUser();
            }
            if (user != null) {
                notificationService.createEmailNotificationForUser(user, NotificationType.INVOICE_ISSUED,
                        "Invoice " + invoice.getInvoiceNumber(), message, "INVOICE", invoice.getId(), dedupe);
                return;
            }
        }
        if (booking != null && booking.getGuestEmail() != null) {
            notificationService.createEmailNotificationForGuest(booking.getGuestEmail(), booking.getGuestPhone(),
                    NotificationType.INVOICE_ISSUED, "Invoice " + invoice.getInvoiceNumber(), message,
                    "INVOICE", invoice.getId(), dedupe + ":" + booking.getGuestEmail());
        }
    }

    private String vatNoteFor(String treatment) {
        if (treatment == null) {
            return null;
        }
        return switch (treatment) {
            case "REVERSE_CHARGE" -> "VAT reverse-charged (Art. 196 EU VAT Directive).";
            case "NOT_REGISTERED" -> "VAT charged; recipient is not VAT-registered.";
            case "OUT_OF_SCOPE" -> "Outside the scope of EU VAT.";
            default -> null;
        };
    }

    private String hostName(LocalProfile host) {
        if (host == null) {
            return null;
        }
        String legal = (safe(host.getLegalFirstName()) + " " + safe(host.getLegalLastName())).trim();
        return legal.isBlank() ? host.getDisplayName() : legal;
    }

    private String customerName(Booking booking) {
        if (booking.getTravelerUser() != null) {
            return booking.getTravelerUser().getFullName();
        }
        return booking.getGuestName();
    }

    private String customerEmail(Booking booking) {
        if (booking.getTravelerUser() != null) {
            return booking.getTravelerUser().getEmail();
        }
        return booking.getGuestEmail();
    }

    private String companyAddress(CompanySettings c) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, c.getAddressLine1());
        appendLine(sb, c.getAddressLine2());
        appendLine(sb, (safe(c.getPostalCode()) + " " + safe(c.getCity())).trim());
        appendLine(sb, c.getCountry());
        return sb.toString().trim();
    }

    private void appendLine(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value.trim()).append("\n");
        }
    }

    private InvoiceResponse toResponse(Invoice i) {
        return new InvoiceResponse(
                i.getId(), i.getInvoiceNumber(), i.getInvoiceType(), i.getStatus(), i.getRecipientType(),
                i.getRecipientName(), i.getCurrency(), i.getSubtotalAmount(), i.getVatAmount(),
                i.getTotalAmount(), i.getVatNote(), i.getBookingId(), i.getPayoutId(), i.getIssuedAt());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
