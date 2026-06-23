package com.localbuddy.trustsafety;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TrustSafetyService {

    private final TrustSafetyReportRepository safetyReportRepository;
    private final UserAccountRestrictionRepository restrictionRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public TrustSafetyService(TrustSafetyReportRepository safetyReportRepository,
                              UserAccountRestrictionRepository restrictionRepository,
                              BookingRepository bookingRepository,
                              UserRepository userRepository) {
        this.safetyReportRepository = safetyReportRepository;
        this.restrictionRepository = restrictionRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public SafetyReportResponse createSafetyReport(UUID reporterUserId, CreateSafetyReportRequest request) {
        User reporter = userRepository.findById(reporterUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Reporter user not found"));

        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        User reportedUser = resolveReportedUser(reporterUserId, booking);

        TrustSafetyReport report = new TrustSafetyReport();
        report.setReporterUser(reporter);
        report.setReportedUser(reportedUser);
        report.setBooking(booking);
        report.setReportType(request.reportType());
        report.setSeverity(request.severity());
        report.setStatus(SafetyReportStatus.OPEN);
        report.setDescription(requiredTrim(request.description()));

        return toSafetyReportResponse(safetyReportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public List<SafetyReportResponse> getAdminReports(SafetyReportStatus status) {
        List<TrustSafetyReport> reports = status == null
                ? safetyReportRepository.findAllByOrderByCreatedAtDesc()
                : safetyReportRepository.findByStatusOrderByCreatedAtDesc(status);

        return reports.stream()
                .map(this::toSafetyReportResponse)
                .toList();
    }

    @Transactional
    public SafetyReportResponse updateReport(UUID reportId, UpdateSafetyReportRequest request) {
        TrustSafetyReport report = safetyReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Safety report not found"));

        if (request.status() != null) {
            report.setStatus(request.status());

            if (request.status() == SafetyReportStatus.RESOLVED ||
                    request.status() == SafetyReportStatus.DISMISSED) {
                report.setResolvedAt(Instant.now());
            }
        }

        if (request.severity() != null) {
            report.setSeverity(request.severity());
        }

        if (request.adminNotes() != null) {
            report.setAdminNotes(optionalTrim(request.adminNotes()));
        }

        return toSafetyReportResponse(safetyReportRepository.save(report));
    }

    @Transactional
    public UserRestrictionResponse createRestriction(UUID adminUserId, CreateUserRestrictionRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        User adminUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));

        UserAccountRestriction restriction = new UserAccountRestriction();
        restriction.setUser(user);
        restriction.setRestrictionType(request.restrictionType());
        restriction.setReason(requiredTrim(request.reason()));
        restriction.setActive(true);
        restriction.setCreatedByAdminUser(adminUser);

        return toRestrictionResponse(restrictionRepository.save(restriction));
    }

    @Transactional
    public UserRestrictionResponse deactivateRestriction(UUID restrictionId) {
        UserAccountRestriction restriction = restrictionRepository.findById(restrictionId)
                .orElseThrow(() -> new ResourceNotFoundException("Restriction not found"));

        restriction.setActive(false);
        restriction.setDeactivatedAt(Instant.now());

        return toRestrictionResponse(restrictionRepository.save(restriction));
    }

    @Transactional(readOnly = true)
    public List<UserRestrictionResponse> getActiveRestrictions() {
        return restrictionRepository.findAllByActiveTrueOrderByCreatedAtDesc()
                .stream()
                .map(this::toRestrictionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public void requireUserNotSuspended(UUID userId) {
        if (restrictionRepository.existsByUserIdAndRestrictionTypeAndActiveTrue(
                userId,
                UserRestrictionType.ACCOUNT_SUSPENDED
        )) {
            throw new BadRequestException("User account is suspended");
        }
    }

    @Transactional(readOnly = true)
    public void requireUserCanBook(UUID userId) {
        boolean restricted = restrictionRepository.existsByUserIdAndRestrictionTypeInAndActiveTrue(
                userId,
                List.of(
                        UserRestrictionType.ACCOUNT_SUSPENDED,
                        UserRestrictionType.BOOKING_BLOCKED
                )
        );

        if (restricted) {
            throw new BadRequestException("User is blocked from booking");
        }
    }

    @Transactional(readOnly = true)
    public void requireUserCanHost(UUID userId) {
        boolean restricted = restrictionRepository.existsByUserIdAndRestrictionTypeInAndActiveTrue(
                userId,
                List.of(
                        UserRestrictionType.ACCOUNT_SUSPENDED,
                        UserRestrictionType.HOSTING_BLOCKED
                )
        );

        if (restricted) {
            throw new BadRequestException("User is blocked from hosting");
        }
    }


    private User resolveReportedUser(UUID reporterUserId, Booking booking) {
        User traveler = booking.getLoggedInUser();
        LocalProfile localProfile = booking.getLocalProfile();
        User localUser = localProfile != null ? localProfile.getUser() : null;

        if (traveler != null && traveler.getId().equals(reporterUserId)) {
            if (localUser == null) {
                throw new BadRequestException("Reported local user not found");
            }
            return localUser;
        }

        if (localUser != null && localUser.getId().equals(reporterUserId)) {
            if (traveler == null) {
                throw new BadRequestException("Guest safety reports are not supported through this endpoint yet");
            }
            return traveler;
        }

        throw new BadRequestException("You can only report bookings you are involved in");
    }

    private SafetyReportResponse toSafetyReportResponse(TrustSafetyReport report) {
        return new SafetyReportResponse(
                report.getId(),
                report.getReporterUser() != null ? report.getReporterUser().getId() : null,
                report.getReportedUser() != null ? report.getReportedUser().getId() : null,
                report.getBooking() != null ? report.getBooking().getId() : null,
                report.getReportType(),
                report.getSeverity(),
                report.getStatus(),
                report.getDescription(),
                report.getAdminNotes(),
                report.getCreatedAt(),
                report.getUpdatedAt(),
                report.getResolvedAt()
        );
    }

    private UserRestrictionResponse toRestrictionResponse(UserAccountRestriction restriction) {
        return new UserRestrictionResponse(
                restriction.getId(),
                restriction.getUser().getId(),
                restriction.getRestrictionType(),
                restriction.getReason(),
                restriction.isActive(),
                restriction.getCreatedByAdminUser() != null
                        ? restriction.getCreatedByAdminUser().getId()
                        : null,
                restriction.getCreatedAt(),
                restriction.getUpdatedAt(),
                restriction.getDeactivatedAt()
        );
    }

    private String requiredTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("Required value is missing");
        }
        return value.trim();
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}