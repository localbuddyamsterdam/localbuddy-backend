package com.localbuddy.booking;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Derives cancellation / no-show reliability counts for hosts, experiences and customers. */
@Service
public class ReliabilityService {

    private final BookingRepository bookingRepository;

    public ReliabilityService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public ReliabilitySummaryResponse forHost(UUID localProfileId) {
        return new ReliabilitySummaryResponse(
                "HOST",
                localProfileId,
                bookingRepository.countByLocalProfileIdAndStatus(localProfileId, BookingStatus.CANCELLED_BY_LOCAL),
                bookingRepository.countDistinctSlotsByLocalProfileAndOutcome(localProfileId, AttendanceOutcome.HOST_NO_SHOW),
                bookingRepository.countByLocalProfileIdAndStatus(localProfileId, BookingStatus.COMPLETED));
    }

    @Transactional(readOnly = true)
    public ReliabilitySummaryResponse forExperience(UUID experienceId) {
        return new ReliabilitySummaryResponse(
                "EXPERIENCE",
                experienceId,
                bookingRepository.countByExperienceIdAndStatus(experienceId, BookingStatus.CANCELLED_BY_LOCAL),
                bookingRepository.countDistinctSlotsByExperienceAndOutcome(experienceId, AttendanceOutcome.HOST_NO_SHOW),
                bookingRepository.countByExperienceIdAndStatus(experienceId, BookingStatus.COMPLETED));
    }

    @Transactional(readOnly = true)
    public ReliabilitySummaryResponse forCustomer(UUID userId) {
        return new ReliabilitySummaryResponse(
                "CUSTOMER",
                userId,
                bookingRepository.countByLoggedInUserIdAndStatus(userId, BookingStatus.CANCELLED_BY_LOGGED_IN_USER),
                bookingRepository.countByLoggedInUserIdAndAttendanceOutcome(userId, AttendanceOutcome.CUSTOMER_NO_SHOW),
                bookingRepository.countByLoggedInUserIdAndStatus(userId, BookingStatus.COMPLETED));
    }
}
