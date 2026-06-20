package com.localbuddy.gdpr;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.UserConsentRepository;
import com.localbuddy.notification.NotificationPreferenceService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GdprService {

    private final UserRepository userRepository;
    private final UserConsentRepository consentRepository;
    private final NotificationPreferenceService preferenceService;
    private final GdprBookingRepository bookingRepository;
    private final GdprPaymentRepository paymentRepository;
    private final GdprReviewRepository reviewRepository;
    private final DataDeletionRequestRepository deletionRequestRepository;

    public GdprService(UserRepository userRepository,
                       UserConsentRepository consentRepository,
                       NotificationPreferenceService preferenceService,
                       GdprBookingRepository bookingRepository,
                       GdprPaymentRepository paymentRepository,
                       GdprReviewRepository reviewRepository,
                       DataDeletionRequestRepository deletionRequestRepository) {
        this.userRepository = userRepository;
        this.consentRepository = consentRepository;
        this.preferenceService = preferenceService;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.reviewRepository = reviewRepository;
        this.deletionRequestRepository = deletionRequestRepository;
    }

    @Transactional(readOnly = true)
    public GdprExportResponse exportMyData(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        GdprExportResponse.ExportAccount account = new GdprExportResponse.ExportAccount(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().name() : null,
                user.getStatus() != null ? user.getStatus().name() : null,
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getCreatedAt()
        );

        List<GdprExportResponse.ExportConsent> consents = consentRepository.findByUserId(userId).stream()
                .map(c -> new GdprExportResponse.ExportConsent(
                        c.getConsentType() != null ? c.getConsentType().name() : null,
                        c.getVersion(),
                        c.getAcceptedAt()))
                .toList();

        List<GdprExportResponse.ExportBooking> bookings =
                bookingRepository.findByTravelerUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(b -> new GdprExportResponse.ExportBooking(
                                b.getBookingReference(),
                                b.getStatus() != null ? b.getStatus().name() : null,
                                b.getExperience() != null ? b.getExperience().getTitle() : null,
                                b.getAvailabilitySlot() != null ? b.getAvailabilitySlot().getStartTime() : null,
                                b.getGuestsCount(),
                                b.getTotalAmount(),
                                b.getCurrency(),
                                b.getCreatedAt()))
                        .toList();

        List<GdprExportResponse.ExportPayment> payments =
                paymentRepository.findByBooking_TravelerUser_IdOrderByCreatedAtDesc(userId).stream()
                        .map(p -> new GdprExportResponse.ExportPayment(
                                p.getBooking() != null ? p.getBooking().getBookingReference() : null,
                                p.getPaymentStatus() != null ? p.getPaymentStatus().name() : null,
                                p.getAmount(),
                                p.getCurrency(),
                                p.getRefundedAmount(),
                                p.getPaidAt(),
                                p.getCreatedAt()))
                        .toList();

        List<GdprExportResponse.ExportReview> reviews =
                reviewRepository.findByReviewerUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(r -> new GdprExportResponse.ExportReview(
                                r.getDirection() != null ? r.getDirection().name() : null,
                                r.getRating(),
                                r.getComment(),
                                r.getStatus() != null ? r.getStatus().name() : null,
                                r.getExperience() != null ? r.getExperience().getTitle() : null,
                                r.getCreatedAt()))
                        .toList();

        return new GdprExportResponse(
                Instant.now(),
                account,
                consents,
                preferenceService.getMyPreferences(userId),
                bookings,
                payments,
                reviews
        );
    }

    @Transactional
    public DataDeletionRequestResponse requestDeletion(UUID userId, CreateDataDeletionRequest request) {
        if (deletionRequestRepository.existsByUserIdAndStatus(userId, DataDeletionStatus.REQUESTED)) {
            throw new BadRequestException("You already have a pending deletion request");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        DataDeletionRequest deletionRequest = new DataDeletionRequest();
        deletionRequest.setUser(user);
        deletionRequest.setReason(request != null ? trimToNull(request.reason()) : null);
        deletionRequest.setStatus(DataDeletionStatus.REQUESTED);
        return DataDeletionRequestResponse.from(deletionRequestRepository.save(deletionRequest));
    }

    @Transactional(readOnly = true)
    public List<DataDeletionRequestResponse> getMyDeletionRequests(UUID userId) {
        return deletionRequestRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(DataDeletionRequestResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DataDeletionRequestResponse> listDeletionRequests(boolean pendingOnly) {
        List<DataDeletionRequest> requests = pendingOnly
                ? deletionRequestRepository.findByStatusOrderByCreatedAtAsc(DataDeletionStatus.REQUESTED)
                : deletionRequestRepository.findAllByOrderByCreatedAtDesc();
        return requests.stream().map(DataDeletionRequestResponse::from).toList();
    }

    /** Admin: anonymize the user's account and mark the request processed. */
    @Transactional
    public DataDeletionRequestResponse processDeletionRequest(UUID requestId, ProcessDataDeletionRequest body) {
        DataDeletionRequest request = requireRequest(requestId);
        if (request.getStatus() != DataDeletionStatus.REQUESTED) {
            throw new BadRequestException("Request is not pending");
        }

        anonymize(request.getUser());

        request.setStatus(DataDeletionStatus.PROCESSED);
        request.setProcessedAt(Instant.now());
        if (body != null) {
            request.setAdminNote(trimToNull(body.adminNote()));
        }
        return DataDeletionRequestResponse.from(deletionRequestRepository.save(request));
    }

    @Transactional
    public DataDeletionRequestResponse rejectDeletionRequest(UUID requestId, ProcessDataDeletionRequest body) {
        DataDeletionRequest request = requireRequest(requestId);
        if (request.getStatus() != DataDeletionStatus.REQUESTED) {
            throw new BadRequestException("Request is not pending");
        }
        request.setStatus(DataDeletionStatus.REJECTED);
        request.setProcessedAt(Instant.now());
        if (body != null) {
            request.setAdminNote(trimToNull(body.adminNote()));
        }
        return DataDeletionRequestResponse.from(deletionRequestRepository.save(request));
    }

    private void anonymize(User user) {
        user.setFullName("Deleted User");
        user.setEmail("deleted+" + user.getId() + "@deleted.localbuddy.invalid");
        user.setPhone(null);
        user.setPasswordHash(null);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);
    }

    private DataDeletionRequest requireRequest(UUID requestId) {
        return deletionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Deletion request not found"));
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
