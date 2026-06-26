package com.localbuddy.attendance;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a brand-new attendance check-in in its own transaction so the caller can recover cleanly from
 * a concurrent double-submit.
 *
 * <p>The V23 partial unique indexes ({@code uq_attendance_guest_per_booking} /
 * {@code uq_attendance_host_per_slot}) guarantee at most one check-in per booking/slot. When two requests
 * race — e.g. a guest double-tapping the button — both can read "no row yet" and both attempt the INSERT;
 * the loser trips the index. By doing that INSERT here under {@link Propagation#REQUIRES_NEW}, only this
 * isolated transaction rolls back. The caller's transaction stays healthy and can re-read the winning row,
 * instead of being poisoned — Spring marks a transaction rollback-only as soon as a participating
 * repository call throws, which would otherwise turn the recovery into an {@code UnexpectedRollbackException}.
 */
@Component
public class AttendanceCheckInWriter {

    private final AttendanceCheckInRepository checkInRepository;

    public AttendanceCheckInWriter(AttendanceCheckInRepository checkInRepository) {
        this.checkInRepository = checkInRepository;
    }

    /**
     * Persists a new check-in and flushes immediately, so a unique-index violation surfaces here (and rolls
     * back only this nested transaction) rather than at the caller's commit.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if a parallel request already inserted
     *         the check-in for this booking/slot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttendanceCheckIn insertNew(AttendanceCheckIn checkIn) {
        return checkInRepository.saveAndFlush(checkIn);
    }
}
