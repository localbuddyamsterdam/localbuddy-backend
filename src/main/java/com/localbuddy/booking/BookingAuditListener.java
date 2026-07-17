package com.localbuddy.booking;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes {@link BookingAuditEvent}s to the audit trail AFTER the booking transaction commits, in a
 * fresh transaction (via {@link BookingAuditService#record}). This ordering means an audit-write
 * failure can never roll back the booking it describes, and a rolled-back booking is never audited.
 */
@Component
public class BookingAuditListener {

    private final BookingAuditService auditService;

    public BookingAuditListener(BookingAuditService auditService) {
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookingAudit(BookingAuditEvent event) {
        auditService.record(
                event.bookingId(),
                event.action(),
                event.detail(),
                event.actorUserId(),
                event.actorRole());
    }
}
