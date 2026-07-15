package com.localbuddy.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentGroupRepository extends JpaRepository<PaymentGroup, UUID> {

    Optional<PaymentGroup> findByGroupToken(String groupToken);

    Optional<PaymentGroup> findByProviderAndProviderCheckoutSessionId(
            PaymentProvider provider,
            String providerCheckoutSessionId
    );

    /**
     * Row lock for every state transition on a group. The paid-webhook and the expiry sweep
     * can fire concurrently right at the pending-payment deadline; locking the group row and
     * re-reading its status serializes them so PAID can never be clobbered by CANCELLED (or
     * the gift card double-returned).
     *
     * <p>IMPORTANT: the locking query must be the FIRST read of the group in the transaction.
     * If the entity is already managed (loaded by an earlier unlocked query), Hibernate takes
     * the DB lock but returns the stale first-level-cache instance — the status branch would
     * then run on pre-lock state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PaymentGroup g where g.id = :groupId")
    Optional<PaymentGroup> findByIdForUpdate(@Param("groupId") UUID groupId);

    /** Locking lookup by Stripe session id — see {@link #findByIdForUpdate} for why the lock must come first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from PaymentGroup g
            where g.provider = :provider and g.providerCheckoutSessionId = :sessionId
            """)
    Optional<PaymentGroup> findBySessionIdForUpdate(
            @Param("provider") PaymentProvider provider,
            @Param("sessionId") String providerCheckoutSessionId
    );
}
