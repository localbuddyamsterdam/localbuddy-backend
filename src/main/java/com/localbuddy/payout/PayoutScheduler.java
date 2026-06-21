package com.localbuddy.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Periodically pays out hosts whose available balance meets the minimum, on the
 * configured cadence ({@code app.payout.schedule}). Runs frequently but only acts
 * on the cadence's payout day; once a host is paid its balance drops below the
 * threshold, so repeat runs are no-ops.
 */
@Service
public class PayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);

    private final HostPayoutService hostPayoutService;
    private final HostLedgerService ledgerService;
    private final String schedule;
    private final BigDecimal minimumAmount;
    private final int dayOfMonth;

    public PayoutScheduler(HostPayoutService hostPayoutService,
                           HostLedgerService ledgerService,
                           @Value("${app.payout.schedule:MONTHLY}") String schedule,
                           @Value("${app.payout.minimum-amount:25}") BigDecimal minimumAmount,
                           @Value("${app.payout.day-of-month:1}") int dayOfMonth) {
        this.hostPayoutService = hostPayoutService;
        this.ledgerService = ledgerService;
        this.schedule = schedule;
        this.minimumAmount = minimumAmount;
        this.dayOfMonth = dayOfMonth;
    }

    @Scheduled(fixedDelayString = "${app.payout.processor-delay-ms:3600000}")
    public void runScheduledPayouts() {
        if (!isPayoutDay()) {
            return;
        }

        List<UUID> hosts = ledgerService.hostsWithAvailableBalance();
        for (UUID hostId : hosts) {
            try {
                HostLedgerBalances balances = ledgerService.balances(hostId);
                if (balances.availableNow().compareTo(minimumAmount) >= 0) {
                    hostPayoutService.createPayoutForHost(hostId);
                }
            } catch (Exception ex) {
                log.warn("Scheduled payout failed for host {}: {}", hostId, ex.getMessage());
            }
        }
    }

    private boolean isPayoutDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String cadence = schedule == null ? "MONTHLY" : schedule.trim().toUpperCase();
        return switch (cadence) {
            case "WEEKLY" -> today.getDayOfWeek().getValue() == 1;
            case "BIWEEKLY" -> today.getDayOfWeek().getValue() == 1 && (today.toEpochDay() / 7) % 2 == 0;
            case "MONTHLY" -> today.getDayOfMonth() == Math.max(1, Math.min(28, dayOfMonth));
            default -> true;
        };
    }
}
