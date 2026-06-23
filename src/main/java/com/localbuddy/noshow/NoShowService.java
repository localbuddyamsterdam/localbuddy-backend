package com.localbuddy.noshow;

import com.localbuddy.booking.AttendanceOutcome;
import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingSource;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles no-show complaints. Either party may file within 48h of the experience start;
 * an admin verifies each report. Approving a HOST no-show fully refunds the customer;
 * approving a CUSTOMER no-show is informational only (the host keeps payment).
 */
@Service
public class NoShowService {

    /** Both parties have this many hours after the experience start to file a no-show. */
    private static final long REPORT_WINDOW_HOURS = 48;

    private final NoShowReportRepository reportRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;

    public NoShowService(NoShowReportRepository reportRepository,
                         BookingRepository bookingRepository,
                         UserRepository userRepository,
                         PaymentService paymentService) {
        this.reportRepository = reportRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
    }

    /** A logged-in customer or host files a no-show complaint on their own booking. */
    @Transactional
    public NoShowReportResponse reportNoShow(UUID actorUserId, UUID bookingId, CreateNoShowReportRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        boolean isCustomer = booking.getLoggedInUser() != null
                && booking.getLoggedInUser().getId().equals(actorUserId);
        boolean isHost = booking.getLocalProfile() != null
                && booking.getLocalProfile().getUser() != null
                && booking.getLocalProfile().getUser().getId().equals(actorUserId);

        if (!isCustomer && !isHost) {
            // Hide the booking's existence from non-parties.
            throw new ResourceNotFoundException("Booking not found");
        }

        // Customer complains about the host; host complains about the customer.
        NoShowSubject subject = isCustomer ? NoShowSubject.HOST : NoShowSubject.CUSTOMER;

        validateReportable(booking, subject);

        User reporter = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        NoShowReport report = new NoShowReport();
        report.setBooking(booking);
        report.setReportedByUser(reporter);
        report.setSubject(subject);
        report.setStatus(NoShowReportStatus.REQUESTED);
        report.setReason(trimToNull(request != null ? request.reason() : null));
        return NoShowReportResponse.from(reportRepository.save(report));
    }

    /**
     * A guest customer (no account) files a host no-show refund claim. The guest is verified by their
     * booking reference + guest email, mirroring the guest payment-lookup flow. Always a HOST report
     * with a null reporter; admin approval issues the full refund as for any host no-show.
     */
    @Transactional
    public NoShowReportResponse reportHostNoShowAsGuest(GuestNoShowReportRequest request) {
        String normalizedReference = request.bookingReference().trim().toUpperCase(Locale.ROOT);
        String normalizedEmail = request.guestEmail().trim().toLowerCase(Locale.ROOT);

        Booking booking = bookingRepository.findByBookingReference(normalizedReference)
                .orElseThrow(() -> new ResourceNotFoundException("Guest booking not found"));

        if (booking.getBookingSource() != BookingSource.GUEST_USER) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        if (booking.getGuestEmail() == null ||
                !booking.getGuestEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new ResourceNotFoundException("Guest booking not found");
        }

        validateReportable(booking, NoShowSubject.HOST);

        NoShowReport report = new NoShowReport();
        report.setBooking(booking);
        report.setReportedByUser(null);
        report.setSubject(NoShowSubject.HOST);
        report.setStatus(NoShowReportStatus.REQUESTED);
        report.setReason(trimToNull(request.reason()));
        return NoShowReportResponse.from(reportRepository.save(report));
    }

    /**
     * Shared no-show eligibility checks: the booking must be confirmed, the experience must have
     * started but still be within the 48-hour reporting window, and no report about the same party
     * may already be open or approved.
     */
    private void validateReportable(Booking booking, NoShowSubject subject) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only confirmed bookings can be reported as a no-show");
        }

        Instant startTime = booking.getAvailabilitySlot().getStartTime();
        Instant now = Instant.now();
        if (now.isBefore(startTime)) {
            throw new BadRequestException("The experience has not started yet");
        }
        if (Duration.between(startTime, now).toHours() >= REPORT_WINDOW_HOURS) {
            throw new BadRequestException(
                    "The 48-hour window to report a no-show has passed. Please contact customer service.");
        }

        if (reportRepository.existsByBookingIdAndSubjectAndStatusIn(
                booking.getId(), subject, List.of(NoShowReportStatus.REQUESTED, NoShowReportStatus.APPROVED))) {
            throw new BadRequestException("A no-show report for this booking is already open");
        }
    }

    @Transactional(readOnly = true)
    public List<NoShowReportResponse> getMyReports(UUID actorUserId) {
        return reportRepository.findByReportedByUserIdOrderByCreatedAtDesc(actorUserId)
                .stream().map(NoShowReportResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<NoShowReportResponse> listReports(boolean pendingOnly) {
        List<NoShowReport> reports = pendingOnly
                ? reportRepository.findByStatusOrderByCreatedAtAsc(NoShowReportStatus.REQUESTED)
                : reportRepository.findAllByOrderByCreatedAtDesc();
        return reports.stream().map(NoShowReportResponse::from).toList();
    }

    /**
     * Admin approves a report. HOST no-show -> full refund to the customer + booking cancelled by admin.
     * CUSTOMER no-show -> booking completed (host keeps payment). Either way the booking is flagged.
     */
    @Transactional
    public NoShowReportResponse approve(UUID reportId, ResolveNoShowReportRequest body) {
        NoShowReport report = requireReport(reportId);
        requirePending(report);

        Booking booking = report.getBooking();
        Instant now = Instant.now();

        if (report.getSubject() == NoShowSubject.HOST) {
            paymentService.fullyRefundBookingPayment(booking, "Host no-show verified by admin");
            booking.setAttendanceOutcome(AttendanceOutcome.HOST_NO_SHOW);
            booking.setStatus(BookingStatus.CANCELLED_BY_ADMIN);
            booking.setCancelledAt(now);
            booking.setCancellationReason("Host no-show verified by admin");
        } else {
            booking.setAttendanceOutcome(AttendanceOutcome.CUSTOMER_NO_SHOW);
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                booking.setStatus(BookingStatus.COMPLETED);
                booking.setCompletedAt(now);
            }
        }
        booking.setNoShowMarkedAt(now);
        bookingRepository.save(booking);

        report.setStatus(NoShowReportStatus.APPROVED);
        report.setResolvedAt(now);
        report.setAdminNote(trimToNull(body != null ? body.adminNote() : null));
        return NoShowReportResponse.from(reportRepository.save(report));
    }

    @Transactional
    public NoShowReportResponse reject(UUID reportId, ResolveNoShowReportRequest body) {
        NoShowReport report = requireReport(reportId);
        requirePending(report);
        report.setStatus(NoShowReportStatus.REJECTED);
        report.setResolvedAt(Instant.now());
        report.setAdminNote(trimToNull(body != null ? body.adminNote() : null));
        return NoShowReportResponse.from(reportRepository.save(report));
    }

    /** Admin clears a previously set no-show flag on a booking (does not reverse any refund). */
    @Transactional
    public void removeFlag(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        booking.setAttendanceOutcome(AttendanceOutcome.NONE);
        booking.setNoShowMarkedAt(null);
        bookingRepository.save(booking);
    }

    private NoShowReport requireReport(UUID reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("No-show report not found"));
    }

    private void requirePending(NoShowReport report) {
        if (report.getStatus() != NoShowReportStatus.REQUESTED) {
            throw new BadRequestException("Report is not pending");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
