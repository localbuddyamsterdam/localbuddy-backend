package com.localbuddy.admin;

import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.review.ReviewRepository;
import com.localbuddy.review.ReviewStatus;
import com.localbuddy.safety.SafetyReportRepository;
import com.localbuddy.safety.SafetyReportStatus;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ExperienceRepository experienceRepository;
    private final BookingRepository bookingRepository;
    private final SafetyReportRepository safetyReportRepository;
    private final ReviewRepository reviewRepository;

    public AdminDashboardService(UserRepository userRepository,
                                 LocalProfileRepository localProfileRepository,
                                 ExperienceRepository experienceRepository,
                                 BookingRepository bookingRepository,
                                 SafetyReportRepository safetyReportRepository,
                                 ReviewRepository reviewRepository) {
        this.userRepository = userRepository;
        this.localProfileRepository = localProfileRepository;
        this.experienceRepository = experienceRepository;
        this.bookingRepository = bookingRepository;
        this.safetyReportRepository = safetyReportRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardSummaryResponse getSummary() {
        long cancelledBookings =
                bookingRepository.countByStatus(BookingStatus.CANCELLED_BY_LOGGED_IN_USER)
                        + bookingRepository.countByStatus(BookingStatus.CANCELLED_BY_LOCAL);

        return new AdminDashboardSummaryResponse(
                userRepository.count(),
                localProfileRepository.count(),
                localProfileRepository.countByApprovalStatus(LocalApprovalStatus.SUBMITTED),
                localProfileRepository.countByApprovalStatus(LocalApprovalStatus.APPROVED),
                experienceRepository.count(),
                experienceRepository.countByStatus(ExperienceStatus.SUBMITTED),
                experienceRepository.countByStatus(ExperienceStatus.APPROVED),
                bookingRepository.count(),
                bookingRepository.countByStatus(BookingStatus.REQUESTED),
                bookingRepository.countByStatus(BookingStatus.ACCEPTED),
                cancelledBookings,
                safetyReportRepository.countByStatus(SafetyReportStatus.OPEN),
                safetyReportRepository.countByStatus(SafetyReportStatus.IN_REVIEW),
                reviewRepository.countByStatus(ReviewStatus.VISIBLE),
                reviewRepository.countByStatus(ReviewStatus.HIDDEN)
        );
    }
}