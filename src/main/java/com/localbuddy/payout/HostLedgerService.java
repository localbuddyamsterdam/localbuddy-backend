package com.localbuddy.payout;

import com.localbuddy.booking.Booking;
import com.localbuddy.payment.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The host earnings ledger: the source of truth for what a host has earned, what
 * is on hold, what is payable, and clawbacks. Earnings post on payment, become
 * payable after the hold window (experience end + {@code app.payout.hold-hours}),
 * and reversals net against future payouts.
 */
@Service
public class HostLedgerService {

    private final HostLedgerEntryRepository ledgerRepository;
    private final long holdHours;

    public HostLedgerService(HostLedgerEntryRepository ledgerRepository,
                             @Value("${app.payout.hold-hours:72}") long holdHours) {
        this.ledgerRepository = ledgerRepository;
        this.holdHours = holdHours;
    }

    /** Posts a host earning for a paid booking (idempotent per payment). */
    @Transactional
    public void recordEarning(Booking booking, Payment payment) {
        if (booking == null || payment == null || booking.getLocalProfile() == null) {
            return;
        }
        if (ledgerRepository.existsByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.EARNING)) {
            return;
        }

        BigDecimal amount = payment.getHostPayoutAmount() != null
                ? payment.getHostPayoutAmount()
                : payment.getLocalPayoutAmount();
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        Instant availableAt = computeAvailableAt(booking);
        Instant now = Instant.now();

        HostLedgerEntry entry = new HostLedgerEntry();
        entry.setLocalProfileId(booking.getLocalProfile().getId());
        entry.setPaymentId(payment.getId());
        entry.setBookingId(booking.getId());
        entry.setEntryType(LedgerEntryType.EARNING);
        entry.setAmount(amount);
        entry.setCurrency(payment.getCurrency());
        entry.setStatus(availableAt.isAfter(now) ? LedgerEntryStatus.PENDING : LedgerEntryStatus.AVAILABLE);
        entry.setAvailableAt(availableAt);
        entry.setDescription("Earning for booking " + booking.getBookingReference());

        try {
            ledgerRepository.save(entry);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent confirm already created the earning; safe to ignore.
        }
    }

    /** Reverses a host's earning when a paid booking is refunded (clawback if already paid out). */
    @Transactional
    public void reverseForPayment(UUID paymentId, String reason) {
        List<HostLedgerEntry> entries = ledgerRepository.findByPaymentId(paymentId);

        HostLedgerEntry earning = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.EARNING)
                .findFirst().orElse(null);
        if (earning == null) {
            return;
        }
        if (earning.getStatus() == LedgerEntryStatus.REVERSED
                || entries.stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.REVERSAL)) {
            return;
        }

        if (earning.getStatus() == LedgerEntryStatus.PENDING
                || earning.getStatus() == LedgerEntryStatus.AVAILABLE) {
            earning.setStatus(LedgerEntryStatus.REVERSED);
            earning.setDescription(append(earning.getDescription(), "Reversed: " + safe(reason)));
            ledgerRepository.save(earning);
            return;
        }

        // Already PAID out — post a negative clawback that nets against future earnings.
        HostLedgerEntry reversal = new HostLedgerEntry();
        reversal.setLocalProfileId(earning.getLocalProfileId());
        reversal.setPaymentId(paymentId);
        reversal.setBookingId(earning.getBookingId());
        reversal.setEntryType(LedgerEntryType.REVERSAL);
        reversal.setAmount(earning.getAmount().negate());
        reversal.setCurrency(earning.getCurrency());
        reversal.setStatus(LedgerEntryStatus.AVAILABLE);
        reversal.setAvailableAt(Instant.now());
        reversal.setDescription("Clawback for refunded booking: " + safe(reason));
        ledgerRepository.save(reversal);
    }

    /** Moves earnings out of the hold window once their available_at has passed. */
    @Scheduled(fixedDelayString = "${app.payout.ledger-release-delay-ms:300000}")
    @Transactional
    public void releaseHolds() {
        List<HostLedgerEntry> due = ledgerRepository
                .findTop500ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
                        LedgerEntryStatus.PENDING, Instant.now());
        for (HostLedgerEntry entry : due) {
            entry.setStatus(LedgerEntryStatus.AVAILABLE);
        }
        ledgerRepository.saveAll(due);
    }

    @Transactional(readOnly = true)
    public HostLedgerBalances balances(UUID hostId) {
        BigDecimal onHold = ledgerRepository.sumByStatus(hostId, LedgerEntryStatus.PENDING);
        BigDecimal availableNow = sum(payableEntries(hostId));
        BigDecimal paidOut = ledgerRepository.sumByStatus(hostId, LedgerEntryStatus.PAID);
        BigDecimal lifetime = ledgerRepository.sumLifetime(hostId);
        return new HostLedgerBalances(onHold, availableNow, paidOut, lifetime, "EUR");
    }

    /** AVAILABLE, not-yet-attached entries — the accumulation since the last payout. */
    @Transactional(readOnly = true)
    public List<HostLedgerEntry> payableEntries(UUID hostId) {
        return ledgerRepository.findByLocalProfileIdAndStatusAndPayoutIdIsNull(hostId, LedgerEntryStatus.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public List<UUID> hostsWithAvailableBalance() {
        return ledgerRepository.findHostsWithAvailableBalance();
    }

    @Transactional
    public void attachToPayout(List<HostLedgerEntry> entries, UUID payoutId) {
        for (HostLedgerEntry entry : entries) {
            entry.setPayoutId(payoutId);
        }
        ledgerRepository.saveAll(entries);
    }

    /** Marks a payout's entries settled. */
    @Transactional
    public void settlePayout(UUID payoutId) {
        List<HostLedgerEntry> entries = ledgerRepository.findByPayoutId(payoutId);
        for (HostLedgerEntry entry : entries) {
            entry.setStatus(LedgerEntryStatus.PAID);
        }
        ledgerRepository.saveAll(entries);
    }

    /** Releases a failed payout's entries back to AVAILABLE for retry. */
    @Transactional
    public void detachPayout(UUID payoutId) {
        List<HostLedgerEntry> entries = ledgerRepository.findByPayoutId(payoutId);
        for (HostLedgerEntry entry : entries) {
            entry.setPayoutId(null);
        }
        ledgerRepository.saveAll(entries);
    }

    private Instant computeAvailableAt(Booking booking) {
        Instant end = booking.getAvailabilitySlot() != null ? booking.getAvailabilitySlot().getEndTime() : null;
        Instant base = end != null ? end : Instant.now();
        return base.plusSeconds(holdHours * 3600);
    }

    private BigDecimal sum(List<HostLedgerEntry> entries) {
        return entries.stream()
                .map(HostLedgerEntry::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String append(String existing, String add) {
        return (existing == null || existing.isBlank()) ? add : existing + " | " + add;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
