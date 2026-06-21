package com.localbuddy.payout;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Host earnings and payouts, backed by the {@link HostLedgerService} ledger.
 * Earnings/holds/clawbacks live in the ledger; a payout batches a host's payable
 * (AVAILABLE, not-yet-attached) entries and disburses via Stripe Connect.
 */
@Service
public class HostPayoutService {

    private final PayoutRepository payoutRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ConnectPayoutProvider connectPayoutProvider;
    private final HostLedgerService ledgerService;

    public HostPayoutService(PayoutRepository payoutRepository,
                             LocalProfileRepository localProfileRepository,
                             ConnectPayoutProvider connectPayoutProvider,
                             HostLedgerService ledgerService) {
        this.payoutRepository = payoutRepository;
        this.localProfileRepository = localProfileRepository;
        this.connectPayoutProvider = connectPayoutProvider;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public HostEarningsResponse getMyEarnings(UUID hostUserId) {
        LocalProfile host = requireHostProfile(hostUserId);
        HostLedgerBalances balances = ledgerService.balances(host.getId());
        return new HostEarningsResponse(
                balances.lifetimeNet(),
                balances.paidOut(),
                balances.availableNow(),
                balances.onHold(),
                balances.currency());
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> getMyPayouts(UUID hostUserId) {
        LocalProfile host = requireHostProfile(hostUserId);
        return payoutRepository.findByLocalProfileIdOrderByCreatedAtDesc(host.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public ConnectOnboardingResponse createConnectOnboarding(UUID hostUserId) {
        LocalProfile host = requireHostProfile(hostUserId);
        if (!connectPayoutProvider.isConfigured()) {
            throw new BadRequestException("Payouts are not available yet (Stripe Connect is not configured)");
        }
        String accountId = connectPayoutProvider.ensureConnectAccount(host);
        host.setStripeConnectAccountId(accountId);
        localProfileRepository.save(host);
        String url = connectPayoutProvider.createOnboardingLink(accountId);
        return new ConnectOnboardingResponse(accountId, url);
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> getAllPayouts() {
        return payoutRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toResponse).toList();
    }

    /** Batches a host's payable ledger entries into a payout and (if onboarded) disburses it. */
    @Transactional
    public PayoutResponse createPayoutForHost(UUID localProfileId) {
        LocalProfile host = localProfileRepository.findById(localProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        List<HostLedgerEntry> entries = ledgerService.payableEntries(host.getId());
        BigDecimal amount = sum(entries).setScale(2, RoundingMode.HALF_UP);

        if (entries.isEmpty() || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("No earnings available to pay out for this host");
        }

        String currency = entries.get(0).getCurrency();

        Payout payout = new Payout();
        payout.setLocalProfile(host);
        payout.setAmount(amount);
        payout.setCurrency(currency);
        payout.setStatus(PayoutStatus.PENDING);
        Payout saved = payoutRepository.save(payout);

        // Reserve the entries against this payout so they can't be paid twice.
        ledgerService.attachToPayout(entries, saved.getId());

        // Auto-disburse via Stripe Connect when the host is onboarded; otherwise leave
        // PENDING for manual/offline disbursement (admin marks it paid).
        if (connectPayoutProvider.isConfigured()
                && host.isPayoutsEnabled()
                && host.getStripeConnectAccountId() != null) {
            try {
                String transferId = connectPayoutProvider.transfer(
                        host.getStripeConnectAccountId(), amount, currency);
                saved.setProviderTransferId(transferId);
                saved.setStatus(PayoutStatus.PAID);
                saved.setPaidAt(Instant.now());
                saved = payoutRepository.save(saved);
                ledgerService.settlePayout(saved.getId());
            } catch (Exception ex) {
                saved.setStatus(PayoutStatus.FAILED);
                saved.setFailureReason(ex.getMessage());
                saved = payoutRepository.save(saved);
                ledgerService.detachPayout(saved.getId());
            }
        }

        return toResponse(saved);
    }

    @Transactional
    public PayoutResponse markPayoutPaid(UUID payoutId, String notes) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
        payout.setStatus(PayoutStatus.PAID);
        payout.setPaidAt(Instant.now());
        if (notes != null && !notes.trim().isEmpty()) {
            payout.setNotes(notes.trim());
        }
        PayoutResponse response = toResponse(payoutRepository.save(payout));
        ledgerService.settlePayout(payoutId);
        return response;
    }

    private LocalProfile requireHostProfile(UUID hostUserId) {
        return localProfileRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));
    }

    private BigDecimal sum(List<HostLedgerEntry> entries) {
        return entries.stream()
                .map(HostLedgerEntry::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PayoutResponse toResponse(Payout payout) {
        return new PayoutResponse(
                payout.getId(),
                payout.getLocalProfile().getId(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getStatus(),
                payout.getProviderTransferId(),
                payout.getNotes(),
                payout.getCreatedAt(),
                payout.getPaidAt()
        );
    }
}
