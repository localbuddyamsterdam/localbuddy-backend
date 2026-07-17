package com.localbuddy.booking;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CancellationRefundPolicyService {

    private final CancellationRefundPolicyRepository policyRepository;

    public CancellationRefundPolicyService(CancellationRefundPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Transactional(readOnly = true)
    public RefundCalculationResult calculateRefund(
            Booking booking,
            BookingCancellationActor cancelledBy
    ) {
        return calculateRefund(booking, cancelledBy, Instant.now());
    }

    /**
     * Refund/fee this booking would attract if cancelled by {@code cancelledBy} at {@code asOf}.
     * Pass {@code Instant.now()} to preview a live cancellation; pass the booking's {@code cancelledAt}
     * to reproduce the tier that applied to an already-cancelled booking (the tier is time-based, so
     * pricing it "now" for a past cancellation would drift once the start time passes).
     */
    @Transactional(readOnly = true)
    public RefundCalculationResult calculateRefund(
            Booking booking,
            BookingCancellationActor cancelledBy,
            Instant asOf
    ) {
        if (booking.getAvailabilitySlot() == null ||
                booking.getAvailabilitySlot().getStartTime() == null) {
            throw new BadRequestException("Booking start time is missing");
        }

        BigDecimal hoursBeforeStart = calculateHoursBeforeStart(
                booking.getAvailabilitySlot().getStartTime(),
                asOf
        );

        CancellationRefundPolicy policy = policyRepository
                .findMatchingPolicies(cancelledBy, hoursBeforeStart)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No active cancellation refund policy found"));

        BigDecimal bookingAmount = booking.getTotalAmount() == null
                ? BigDecimal.ZERO
                : booking.getTotalAmount().setScale(2, RoundingMode.HALF_UP);

        BigDecimal refundAmount = bookingAmount
                .multiply(policy.getRefundPercentage())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .min(bookingAmount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return new RefundCalculationResult(
                cancelledBy,
                hoursBeforeStart,
                policy.getRefundPercentage(),
                refundAmount
        );
    }

    @Transactional(readOnly = true)
    public List<CancellationRefundPolicyResponse> getPolicies() {
        return policyRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CancellationRefundPolicyResponse createPolicy(UpsertCancellationRefundPolicyRequest request) {
        validatePolicyWindow(request);

        CancellationRefundPolicy policy = new CancellationRefundPolicy();
        policy.setId(UUID.randomUUID());
        applyRequest(policy, request);

        return toResponse(policyRepository.save(policy));
    }

    @Transactional
    public CancellationRefundPolicyResponse updatePolicy(UUID policyId, UpsertCancellationRefundPolicyRequest request) {
        validatePolicyWindow(request);

        CancellationRefundPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation refund policy not found"));

        applyRequest(policy, request);

        return toResponse(policyRepository.save(policy));
    }

    @Transactional
    public CancellationRefundPolicyResponse deactivatePolicy(UUID policyId) {
        CancellationRefundPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation refund policy not found"));

        policy.setActive(false);

        return toResponse(policyRepository.save(policy));
    }

    private void applyRequest(CancellationRefundPolicy policy, UpsertCancellationRefundPolicyRequest request) {
        policy.setName(requiredTrim(request.name()));
        policy.setCancelledBy(request.cancelledBy());
        policy.setMinHoursBeforeStart(request.minHoursBeforeStart());
        policy.setMaxHoursBeforeStart(request.maxHoursBeforeStart());
        policy.setRefundPercentage(request.refundPercentage());
        policy.setActive(request.active() == null || request.active());
    }

    private void validatePolicyWindow(UpsertCancellationRefundPolicyRequest request) {
        if (request.maxHoursBeforeStart() != null &&
                request.maxHoursBeforeStart().compareTo(request.minHoursBeforeStart()) <= 0) {
            throw new BadRequestException("Maximum hours must be greater than minimum hours");
        }
    }

    private CancellationRefundPolicyResponse toResponse(CancellationRefundPolicy policy) {
        return new CancellationRefundPolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getCancelledBy(),
                policy.getMinHoursBeforeStart(),
                policy.getMaxHoursBeforeStart(),
                policy.getRefundPercentage(),
                policy.isActive(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }

    private String requiredTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("Required value is missing");
        }
        return value.trim();
    }


    private BigDecimal calculateHoursBeforeStart(Instant startTime, Instant asOf) {
        long minutes = Duration.between(asOf, startTime).toMinutes();

        if (minutes < 0) {
            minutes = 0;
        }

        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.DOWN);
    }
}