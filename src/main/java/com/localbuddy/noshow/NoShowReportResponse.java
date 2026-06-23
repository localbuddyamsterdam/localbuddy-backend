package com.localbuddy.noshow;

import java.time.Instant;
import java.util.UUID;

public record NoShowReportResponse(
        UUID id,
        UUID bookingId,
        String bookingReference,
        UUID reportedByUserId,
        NoShowSubject subject,
        NoShowReportStatus status,
        String reason,
        String adminNote,
        Instant createdAt,
        Instant resolvedAt
) {
    public static NoShowReportResponse from(NoShowReport report) {
        return new NoShowReportResponse(
                report.getId(),
                report.getBooking() != null ? report.getBooking().getId() : null,
                report.getBooking() != null ? report.getBooking().getBookingReference() : null,
                report.getReportedByUser() != null ? report.getReportedByUser().getId() : null,
                report.getSubject(),
                report.getStatus(),
                report.getReason(),
                report.getAdminNote(),
                report.getCreatedAt(),
                report.getResolvedAt()
        );
    }
}
