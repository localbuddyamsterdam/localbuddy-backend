package com.localbuddy.booking;

import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records and reads the admin action history on a booking. Writing is best-effort —
 * a failed audit never breaks the action it describes (which has already committed).
 */
@Service
public class BookingAuditService {

    private static final Logger log = LoggerFactory.getLogger(BookingAuditService.class);

    private final BookingChangeAuditRepository auditRepository;
    private final UserRepository userRepository;

    public BookingAuditService(BookingChangeAuditRepository auditRepository, UserRepository userRepository) {
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
    }

    /** Record an admin action on a booking. Never throws — logs and moves on. */
    @Transactional
    public void record(UUID bookingId, String action, String detail, UUID adminUserId) {
        try {
            BookingChangeAudit entry = new BookingChangeAudit();
            entry.setBookingId(bookingId);
            entry.setAction(action);
            entry.setDetail(detail);
            entry.setChangedByUserId(adminUserId);
            auditRepository.save(entry);
        } catch (Exception ex) {
            log.warn("Failed to record booking audit (booking={}, action={}): {}", bookingId, action, ex.getMessage());
        }
    }

    /** Booking action history, newest first, with the acting admin's display name resolved. */
    @Transactional(readOnly = true)
    public List<BookingAuditResponse> getEvents(UUID bookingId) {
        List<BookingChangeAudit> entries = auditRepository.findByBookingIdOrderByChangedAtDesc(bookingId);
        List<UUID> adminIds = entries.stream()
                .map(BookingChangeAudit::getChangedByUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<UUID, String> names = userRepository.findAllById(adminIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return entries.stream()
                .map(e -> new BookingAuditResponse(
                        e.getId(),
                        e.getAction(),
                        e.getDetail(),
                        e.getChangedByUserId(),
                        e.getChangedByUserId() != null ? names.get(e.getChangedByUserId()) : null,
                        e.getChangedAt()))
                .toList();
    }
}
