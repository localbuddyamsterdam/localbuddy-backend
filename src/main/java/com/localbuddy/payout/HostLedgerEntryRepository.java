package com.localbuddy.payout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HostLedgerEntryRepository extends JpaRepository<HostLedgerEntry, UUID> {

    boolean existsByPaymentIdAndEntryType(UUID paymentId, LedgerEntryType entryType);

    List<HostLedgerEntry> findByPaymentId(UUID paymentId);

    List<HostLedgerEntry> findByLocalProfileIdAndStatus(UUID localProfileId, LedgerEntryStatus status);

    List<HostLedgerEntry> findByLocalProfileIdAndStatusAndPayoutIdIsNull(UUID localProfileId, LedgerEntryStatus status);

    List<HostLedgerEntry> findByPayoutId(UUID payoutId);

    List<HostLedgerEntry> findTop500ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAsc(
            LedgerEntryStatus status, Instant cutoff);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from HostLedgerEntry e
            where e.localProfileId = :hostId and e.status = :status
            """)
    java.math.BigDecimal sumByStatus(@Param("hostId") UUID hostId, @Param("status") LedgerEntryStatus status);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from HostLedgerEntry e
            where e.localProfileId = :hostId and e.status <> com.localbuddy.payout.LedgerEntryStatus.REVERSED
            """)
    java.math.BigDecimal sumLifetime(@Param("hostId") UUID hostId);

    @Query("""
            select distinct e.localProfileId
            from HostLedgerEntry e
            where e.status = com.localbuddy.payout.LedgerEntryStatus.AVAILABLE and e.payoutId is null
            """)
    List<UUID> findHostsWithAvailableBalance();
}
