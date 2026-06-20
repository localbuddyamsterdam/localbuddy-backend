package com.localbuddy.availability;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AvailabilitySlotService {

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final ExperienceRepository experienceRepository;
    private final LocalProfileRepository localProfileRepository;

    public AvailabilitySlotService(AvailabilitySlotRepository availabilitySlotRepository,
                                   ExperienceRepository experienceRepository,
                                   LocalProfileRepository localProfileRepository) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.experienceRepository = experienceRepository;
        this.localProfileRepository = localProfileRepository;
    }

    @Transactional
    public AvailabilitySlotResponse createMyAvailabilitySlot(UUID userId, CreateAvailabilitySlotRequest request) {
        validateTimeRange(request.startTime(), request.endTime());

        LocalProfile localProfile = getApprovedLocalProfile(userId);

        Experience experience = experienceRepository.findById(request.experienceId())
                .orElseThrow(() -> new BadRequestException("Invalid experience"));

        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new BadRequestException("You can create availability only for your own experience");
        }

        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience must be approved before adding availability");
        }

        AvailabilitySlot slot = new AvailabilitySlot();
        slot.setExperience(experience);
        slot.setLocalProfile(localProfile);
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setCapacity(request.capacity());
        slot.setBookedCount(0);
        slot.setStatus(AvailabilityStatus.AVAILABLE);

        return toResponse(availabilitySlotRepository.save(slot));
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlotResponse> getMyAvailabilitySlots(UUID userId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);

        return availabilitySlotRepository.findByLocalProfileIdOrderByStartTimeAsc(localProfile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private LocalProfile getApprovedLocalProfile(UUID userId) {
        LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        if (localProfile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Local profile must be approved");
        }

        return localProfile;
    }

    private void validateTimeRange(Instant startTime, Instant endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException("End time must be after start time");
        }
    }

    private AvailabilitySlotResponse toResponse(AvailabilitySlot slot) {
        int remainingCapacity = slot.getCapacity() - slot.getBookedCount();

        // A private (whole-slot) booking is only offered while no seats have been
        // booked yet, so a later individual booking can never collide with it.
        boolean privateBookingAvailable =
                slot.getStatus() == AvailabilityStatus.AVAILABLE && slot.getBookedCount() == 0;

        return new AvailabilitySlotResponse(
                slot.getId(),
                slot.getExperience().getId(),
                slot.getLocalProfile().getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getCapacity(),
                slot.getBookedCount(),
                remainingCapacity,
                privateBookingAvailable,
                slot.getStatus(),
                slot.getCreatedAt(),
                slot.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlotResponse> getPublicAvailabilityForExperience(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new BadRequestException("Invalid experience"));

        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience is not available");
        }

        return availabilitySlotRepository
                .findByExperienceIdAndStatusAndStartTimeAfterOrderByStartTimeAsc(
                        experienceId,
                        AvailabilityStatus.AVAILABLE,
                        Instant.now()
                )
                .stream()
                .filter(slot -> slot.getBookedCount() < slot.getCapacity())
                .map(this::toResponse)
                .toList();
    }
}