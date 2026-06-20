package com.localbuddy.waitlist;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.availability.AvailabilityStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WaitlistService {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           AvailabilitySlotRepository availabilitySlotRepository,
                           UserRepository userRepository,
                           NotificationService notificationService) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public WaitlistResponse joinAsUser(UUID userId, UUID slotId, JoinWaitlistRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        AvailabilitySlot slot = availabilitySlotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        validateJoinable(slot, request.guestsCount());

        WaitlistEntry entry = new WaitlistEntry();
        entry.setAvailabilitySlot(slot);
        entry.setExperience(slot.getExperience());
        entry.setUser(user);
        entry.setGuestsCount(request.guestsCount());
        entry.setStatus(WaitlistStatus.WAITING);

        return saveEntry(entry, "You are already on the waitlist for this slot");
    }

    @Transactional
    public WaitlistResponse joinAsGuest(UUID slotId, JoinGuestWaitlistRequest request) {
        AvailabilitySlot slot = availabilitySlotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        validateJoinable(slot, request.guestsCount());

        WaitlistEntry entry = new WaitlistEntry();
        entry.setAvailabilitySlot(slot);
        entry.setExperience(slot.getExperience());
        entry.setGuestName(request.guestName().trim());
        entry.setGuestEmail(request.guestEmail().trim().toLowerCase(Locale.ROOT));
        entry.setGuestPhone(request.guestPhone().trim());
        entry.setGuestsCount(request.guestsCount());
        entry.setStatus(WaitlistStatus.WAITING);

        return saveEntry(entry, "You are already on the waitlist for this slot");
    }

    private WaitlistResponse saveEntry(WaitlistEntry entry, String duplicateMessage) {
        try {
            return toResponse(waitlistEntryRepository.save(entry));
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException(duplicateMessage);
        }
    }

    @Transactional
    public void leaveAsUser(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));

        if (entry.getUser() == null || !entry.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Waitlist entry not found");
        }

        entry.setStatus(WaitlistStatus.CANCELLED);
        waitlistEntryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<WaitlistResponse> getMyWaitlist(UUID userId) {
        return waitlistEntryRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Called whenever seats free up on a slot. Every person currently waiting is
     * emailed (and notified in-app, for logged-in users) that a spot opened — it
     * is first-come, first-served, so whoever books and pays first gets it.
     * Notified entries move to NOTIFIED so they are not emailed repeatedly.
     */
    @Transactional
    public void notifyOpenedSpots(AvailabilitySlot slot) {
        if (slot == null || slot.getStatus() != AvailabilityStatus.AVAILABLE) {
            return;
        }

        int remaining = slot.getCapacity() - slot.getBookedCount();
        if (remaining <= 0) {
            return;
        }

        List<WaitlistEntry> waiting = waitlistEntryRepository
                .findByAvailabilitySlotIdAndStatusOrderByCreatedAtAsc(slot.getId(), WaitlistStatus.WAITING);

        if (waiting.isEmpty()) {
            return;
        }

        String experienceTitle = slot.getExperience() != null ? slot.getExperience().getTitle() : "your experience";
        String subject = "A spot just opened up";
        String message = "Good news! A spot just opened for \"" + experienceTitle + "\". "
                + "Spots are first-come, first-served — open LocalBuddy and complete your booking to secure it.";

        Instant now = Instant.now();
        for (WaitlistEntry entry : waiting) {
            String dedupeKey = "WAITLIST_SPOT:" + entry.getId();

            if (entry.getUser() != null) {
                notificationService.createEmailAndInAppNotificationForUser(
                        entry.getUser(), NotificationType.WAITLIST_SPOT_AVAILABLE,
                        subject, message, "AVAILABILITY_SLOT", slot.getId(), dedupeKey
                );
            } else {
                notificationService.createEmailNotificationForGuest(
                        entry.getGuestEmail(), entry.getGuestPhone(), NotificationType.WAITLIST_SPOT_AVAILABLE,
                        subject, message, "AVAILABILITY_SLOT", slot.getId(), dedupeKey
                );
            }

            entry.setStatus(WaitlistStatus.NOTIFIED);
            entry.setNotifiedAt(now);
        }

        waitlistEntryRepository.saveAll(waiting);
    }

    private void validateJoinable(AvailabilitySlot slot, int guestsCount) {
        if (slot.getStartTime() == null || !slot.getStartTime().isAfter(Instant.now())) {
            throw new BadRequestException("Cannot join the waitlist for a past slot");
        }

        if (slot.getStatus() == AvailabilityStatus.CANCELLED) {
            throw new BadRequestException("This slot has been cancelled");
        }

        int remaining = slot.getCapacity() - slot.getBookedCount();
        if (slot.getStatus() == AvailabilityStatus.AVAILABLE && remaining >= guestsCount) {
            throw new BadRequestException(
                    "Seats are still available — please book directly instead of joining the waitlist");
        }
    }

    private WaitlistResponse toResponse(WaitlistEntry entry) {
        return new WaitlistResponse(
                entry.getId(),
                entry.getAvailabilitySlot().getId(),
                entry.getExperience().getId(),
                entry.getUser() != null ? entry.getUser().getId() : null,
                entry.getGuestEmail(),
                entry.getGuestsCount(),
                entry.getStatus(),
                entry.getCreatedAt(),
                entry.getNotifiedAt()
        );
    }
}
