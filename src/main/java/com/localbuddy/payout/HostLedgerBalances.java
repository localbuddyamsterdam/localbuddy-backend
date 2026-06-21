package com.localbuddy.payout;

import java.math.BigDecimal;

/**
 * A host's ledger position.
 *
 * @param onHold       earned but still within the hold window (not yet payable)
 * @param availableNow payable in the next payout (AVAILABLE, not yet attached) — i.e. the
 *                     balance accumulated since the last payout
 * @param paidOut      total settled into paid payouts
 * @param lifetimeNet  net lifetime earnings (excludes reversed entries)
 */
public record HostLedgerBalances(
        BigDecimal onHold,
        BigDecimal availableNow,
        BigDecimal paidOut,
        BigDecimal lifetimeNet,
        String currency
) {
}
