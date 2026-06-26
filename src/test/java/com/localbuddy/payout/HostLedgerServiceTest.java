package com.localbuddy.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the proportional host clawback: the host loses only the share of their
 * earning that the customer is actually refunded (0% refund => nothing reversed).
 */
class HostLedgerServiceTest {

    private final HostLedgerEntryRepository repo = mock(HostLedgerEntryRepository.class);
    private final HostLedgerService service = new HostLedgerService(repo, 72);

    private HostLedgerEntry earning(String amount, LedgerEntryStatus status) {
        HostLedgerEntry e = new HostLedgerEntry();
        e.setLocalProfileId(UUID.randomUUID());
        e.setPaymentId(UUID.randomUUID());
        e.setBookingId(UUID.randomUUID());
        e.setEntryType(LedgerEntryType.EARNING);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency("EUR");
        e.setStatus(status);
        return e;
    }

    @Test
    @DisplayName("0% refund leaves the host's earning fully intact")
    void zeroRefundReversesNothing() {
        service.reverseForPayment(UUID.randomUUID(), BigDecimal.ZERO, "late cancel, no refund");
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("50% refund on a not-yet-paid earning reduces it by half")
    void partialRefundReducesUnpaidEarning() {
        HostLedgerEntry earning = earning("80.00", LedgerEntryStatus.AVAILABLE);
        when(repo.findByPaymentId(any())).thenReturn(List.of(earning));

        service.reverseForPayment(UUID.randomUUID(), new BigDecimal("0.5"), "50% refund");

        assertEquals(new BigDecimal("40.00"), earning.getAmount());
        assertEquals(LedgerEntryStatus.AVAILABLE, earning.getStatus());
        verify(repo).save(earning);
    }

    @Test
    @DisplayName("100% refund on a not-yet-paid earning reverses it entirely")
    void fullRefundReversesUnpaidEarning() {
        HostLedgerEntry earning = earning("80.00", LedgerEntryStatus.AVAILABLE);
        when(repo.findByPaymentId(any())).thenReturn(List.of(earning));

        service.reverseForPayment(UUID.randomUUID(), BigDecimal.ONE, "full refund");

        assertEquals(LedgerEntryStatus.REVERSED, earning.getStatus());
        verify(repo).save(earning);
    }

    @Test
    @DisplayName("50% refund on an already-paid earning posts a -50% clawback")
    void partialRefundClawsBackPaidEarning() {
        HostLedgerEntry earning = earning("80.00", LedgerEntryStatus.PAID);
        when(repo.findByPaymentId(any())).thenReturn(List.of(earning));

        service.reverseForPayment(UUID.randomUUID(), new BigDecimal("0.5"), "50% refund");

        ArgumentCaptor<HostLedgerEntry> captor = ArgumentCaptor.forClass(HostLedgerEntry.class);
        verify(repo).save(captor.capture());
        HostLedgerEntry reversal = captor.getValue();
        assertEquals(LedgerEntryType.REVERSAL, reversal.getEntryType());
        assertEquals(new BigDecimal("-40.00"), reversal.getAmount());
        assertEquals(LedgerEntryStatus.AVAILABLE, reversal.getStatus());
    }
}
