package com.localbuddy.payout;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.payment.Payment;
import com.localbuddy.payment.PaymentRepository;
import com.localbuddy.payment.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class HostPayoutService {

    private final PayoutRepository payoutRepository;
    private final PayoutItemRepository payoutItemRepository;
    private final PaymentRepository paymentRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ConnectPayoutProvider connectPayoutProvider;

    public HostPayoutService(PayoutRepository payoutRepository,
                             PayoutItemRepository payoutItemRepository,
                             PaymentRepository paymentRepository,
                             LocalProfileRepository localProfileRepository,
                             ConnectPayoutProvider connectPayoutProvider) {
        this.payoutRepository = payoutRepository;
        this.payoutItemRepository = payoutItemRepository;
        this.paymentRepository = paymentRepository;
        this.localProfileRepository = localProfileRepository;
        this.connectPayoutProvider = connectPayoutProvider;
    }

    @Transactional(readOnly = true)
    public HostEarningsResponse getMyEarnings(UUID hostUserId) {
        LocalProfile host = requireHostProfile(hostUserId);

        List<Payment> paid = paymentRepository.findByBooking_LocalProfile_IdAndPaymentStatus(
                host.getId(), PaymentStatus.PAID);
        BigDecimal earned = sum(paid.stream().map(Payment::getLocalPayoutAmount).toList());

        List<Payout> payouts = payoutRepository.findByLocalProfileIdOrderByCreatedAtDesc(host.getId());
        BigDecimal paidOut = sum(payouts.stream()
                .filter(p -> p.getStatus() == PayoutStatus.PAID)
                .map(Payout::getAmount).toList());
        BigDecimal committed = sum(payouts.stream()
                .filter(p -> p.getStatus() != PayoutStatus.FAILED)
                .map(Payout::getAmount).toList());

        BigDecimal pending = earned.subtract(committed).max(BigDecimal.ZERO);
        String currency = paid.isEmpty() ? "EUR" : paid.get(0).getCurrency();

        return new HostEarningsResponse(earned, paidOut, pending, currency);
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

    /** Admin: disburse all not-yet-paid-out host earnings as a single payout. */
    @Transactional
    public PayoutResponse createPayoutForHost(UUID localProfileId) {
        LocalProfile host = localProfileRepository.findById(localProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        List<Payment> unpaid = paymentRepository
                .findByBooking_LocalProfile_IdAndPaymentStatus(host.getId(), PaymentStatus.PAID)
                .stream()
                .filter(p -> !payoutItemRepository.existsByPaymentId(p.getId()))
                .toList();

        if (unpaid.isEmpty()) {
            throw new BadRequestException("No earnings available to pay out for this host");
        }

        BigDecimal amount = sum(unpaid.stream().map(Payment::getLocalPayoutAmount).toList());
        String currency = unpaid.get(0).getCurrency();

        Payout payout = new Payout();
        payout.setLocalProfile(host);
        payout.setAmount(amount);
        payout.setCurrency(currency);
        payout.setStatus(PayoutStatus.PENDING);
        Payout saved = payoutRepository.save(payout);

        for (Payment payment : unpaid) {
            PayoutItem item = new PayoutItem();
            item.setPayout(saved);
            item.setPayment(payment);
            item.setAmount(payment.getLocalPayoutAmount());
            payoutItemRepository.save(item);
        }

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
            } catch (Exception ex) {
                saved.setStatus(PayoutStatus.FAILED);
                saved.setFailureReason(ex.getMessage());
            }
            saved = payoutRepository.save(saved);
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
        return toResponse(payoutRepository.save(payout));
    }

    private LocalProfile requireHostProfile(UUID hostUserId) {
        return localProfileRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
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
