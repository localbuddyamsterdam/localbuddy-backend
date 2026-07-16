package com.localbuddy.availability;

import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.BookingMode;
import com.localbuddy.experience.City;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AvailabilitySlotService {

    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final ExperienceRepository experienceRepository;
    private final LocalProfileRepository localProfileRepository;
    private final BookingRepository bookingRepository;
    private final BookingWindowPolicy bookingWindowPolicy;

    public AvailabilitySlotService(AvailabilitySlotRepository availabilitySlotRepository,
                                   ExperienceRepository experienceRepository,
                                   LocalProfileRepository localProfileRepository,
                                   BookingRepository bookingRepository,
                                   BookingWindowPolicy bookingWindowPolicy) {
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.experienceRepository = experienceRepository;
        this.localProfileRepository = localProfileRepository;
        this.bookingRepository = bookingRepository;
        this.bookingWindowPolicy = bookingWindowPolicy;
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

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Amsterdam");
    private static final int MAX_RANGE_DAYS = 120;
    private static final int MAX_SLOTS_PER_CALL = 300;

    /**
     * Bulk-creates slots from a weekly schedule over a date range. Wall-clock
     * times in the requested zone (DST-safe); duplicates (same experience +
     * start) and past times are skipped, and the whole call is atomic.
     */
    @Transactional
    public GenerateAvailabilityResponse generateMyAvailabilitySlots(UUID userId, GenerateAvailabilityRequest request) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        Experience experience = experienceRepository.findById(request.experienceId())
                .orElseThrow(() -> new BadRequestException("Invalid experience"));
        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new BadRequestException("You can create availability only for your own experience");
        }
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience must be approved before adding availability");
        }

        if (request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("End date must be on or after the start date");
        }
        if (Duration.between(request.startDate().atStartOfDay(), request.endDate().atStartOfDay())
                .toDays() > MAX_RANGE_DAYS) {
            throw new BadRequestException("Date range cannot exceed " + MAX_RANGE_DAYS + " days per generation");
        }

        ZoneId zone;
        try {
            zone = request.timezone() == null || request.timezone().isBlank()
                    ? resolveZone(experience) : ZoneId.of(request.timezone());
        } catch (Exception ex) {
            throw new BadRequestException("Unknown timezone: " + request.timezone());
        }

        int durationMinutes = request.durationMinutes() != null
                ? request.durationMinutes()
                : experience.getDurationMinutes();
        if (durationMinutes == 0) {
            throw new BadRequestException("Duration is required");
        }

        Map<DayOfWeek, List<LocalTime>> schedule = request.weekly().stream().collect(Collectors.toMap(
                GenerateAvailabilityRequest.WeeklyRule::dayOfWeek,
                rule -> rule.times().stream().distinct().map(LocalTime::parse).sorted().toList(),
                (a, b) -> {
                    throw new BadRequestException("Each day of the week may appear only once");
                }));

        // One query for existing starts in the window, to skip duplicates cheaply.
        Instant windowFrom = request.startDate().atStartOfDay(zone).toInstant();
        Instant windowTo = request.endDate().plusDays(1).atStartOfDay(zone).toInstant();
        Set<Instant> existingStarts = availabilitySlotRepository
                .findByExperienceIdAndStartTimeBetween(experience.getId(), windowFrom, windowTo)
                .stream().map(AvailabilitySlot::getStartTime).collect(Collectors.toCollection(HashSet::new));

        Instant now = Instant.now();
        List<AvailabilitySlot> toCreate = new ArrayList<>();
        int skippedExisting = 0;
        int skippedPast = 0;
        for (LocalDate date = request.startDate(); !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            List<LocalTime> times = schedule.get(date.getDayOfWeek());
            if (times == null) {
                continue;
            }
            for (LocalTime time : times) {
                Instant start = date.atTime(time).atZone(zone).toInstant();
                if (!start.isAfter(now)) {
                    skippedPast++;
                    continue;
                }
                if (existingStarts.contains(start)) {
                    skippedExisting++;
                    continue;
                }
                AvailabilitySlot slot = new AvailabilitySlot();
                slot.setExperience(experience);
                slot.setLocalProfile(localProfile);
                slot.setStartTime(start);
                slot.setEndTime(start.plus(Duration.ofMinutes(durationMinutes)));
                slot.setCapacity(request.capacity());
                slot.setBookedCount(0);
                slot.setStatus(AvailabilityStatus.AVAILABLE);
                toCreate.add(slot);
                existingStarts.add(start);
                if (toCreate.size() > MAX_SLOTS_PER_CALL) {
                    throw new BadRequestException(
                            "This schedule would create more than " + MAX_SLOTS_PER_CALL
                            + " slots — narrow the date range and generate in batches");
                }
            }
        }

        List<AvailabilitySlotResponse> created = availabilitySlotRepository.saveAll(toCreate)
                .stream().map(this::toResponse).toList();
        return new GenerateAvailabilityResponse(created.size(), skippedExisting, skippedPast, created);
    }

    /**
     * Blocks a slot so no new bookings can be made against it. A slot that still has active bookings
     * cannot be blocked — the host must cancel those bookings first (only possible more than 24h
     * before start). Inside 24h, a booked slot is therefore fully frozen.
     */
    @Transactional
    public AvailabilitySlotResponse blockMyAvailabilitySlot(UUID userId, UUID slotId) {
        AvailabilitySlot slot = loadOwnedSlotForUpdate(userId, slotId);
        requireNoActiveBookings(slotId);
        slot.setStatus(AvailabilityStatus.BLOCKED);
        return toResponse(availabilitySlotRepository.save(slot));
    }

    /** Re-opens a previously blocked slot for bookings. */
    @Transactional
    public AvailabilitySlotResponse unblockMyAvailabilitySlot(UUID userId, UUID slotId) {
        AvailabilitySlot slot = loadOwnedSlotForUpdate(userId, slotId);
        slot.setStatus(AvailabilityStatus.AVAILABLE);
        return toResponse(availabilitySlotRepository.save(slot));
    }

    /** Deletes a slot. Rejected while the slot still has active bookings. */
    @Transactional
    public void deleteMyAvailabilitySlot(UUID userId, UUID slotId) {
        AvailabilitySlot slot = loadOwnedSlotForUpdate(userId, slotId);
        requireNoActiveBookings(slotId);
        availabilitySlotRepository.delete(slot);
    }

    private AvailabilitySlot loadOwnedSlotForUpdate(UUID userId, UUID slotId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));
        if (!slot.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Availability slot not found");
        }
        return slot;
    }

    private void requireNoActiveBookings(UUID slotId) {
        if (bookingRepository.existsByAvailabilitySlotIdAndStatusIn(slotId, BookingStatus.ACTIVE)) {
            throw new BadRequestException(
                    "This slot has active bookings. Cancel the bookings first (more than 24h before start), "
                    + "then you can block or remove the slot.");
        }
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

    /** The zone an experience's wall-clock availability is expressed in: its city's, falling back to the platform default. */
    private ZoneId resolveZone(Experience experience) {
        City city = experience.getCity();
        if (city != null && city.getTimezone() != null && !city.getTimezone().isBlank()) {
            try {
                return ZoneId.of(city.getTimezone());
            } catch (Exception ignored) {
                // fall through to the platform default
            }
        }
        return DEFAULT_ZONE;
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

        Instant now = Instant.now();
        return availabilitySlotRepository
                .findByExperienceIdAndStatusAndStartTimeAfterOrderByStartTimeAsc(
                        experienceId,
                        AvailabilityStatus.AVAILABLE,
                        now
                )
                .stream()
                .filter(slot -> slot.getBookedCount() < slot.getCapacity())
                .filter(slot -> bookingWindowPolicy.isBookableAt(slot, now))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Bookable slots (still-open booking window, at least {@code minRemainingCapacity} seats left)
     * for every APPROVED experience in a city within [from, to). Experience, host profile,
     * category and city come fetch-joined, so callers may read them after the transaction.
     * Used by the AI trip planner to ground itineraries in real, bookable inventory.
     */
    @Transactional(readOnly = true)
    public List<AvailabilitySlot> getBookableSlotsForCityBetween(
            String citySlug,
            Instant from,
            Instant to,
            int minRemainingCapacity
    ) {
        Instant now = Instant.now();
        return availabilitySlotRepository
                .findBookableInCityBetween(
                        citySlug,
                        ExperienceStatus.APPROVED,
                        List.of(BookingMode.SHARED, BookingMode.PRIVATE_ALLOWED),
                        AvailabilityStatus.AVAILABLE,
                        from,
                        to)
                .stream()
                .filter(slot -> slot.getCapacity() - slot.getBookedCount() >= minRemainingCapacity)
                .filter(slot -> bookingWindowPolicy.isBookableAt(slot, now))
                .toList();
    }

    /**
     * Slots bookable as a private whole-slot buyout for a party of {@code partySize} in a city
     * within [from, to): the experience must offer private booking with a private price and not
     * be listed on an external platform, and the slot must be completely empty (the first shared
     * guest removes the private option) with capacity for the whole party. Mirrors the rules
     * {@code BookingService.requirePrivateBookingAllowed} enforces at booking time. Used by the
     * AI trip planner's private-tour mode.
     */
    @Transactional(readOnly = true)
    public List<AvailabilitySlot> getPrivateBuyoutSlotsForCityBetween(
            String citySlug,
            Instant from,
            Instant to,
            int partySize
    ) {
        Instant now = Instant.now();
        return availabilitySlotRepository
                .findBookableInCityBetween(
                        citySlug,
                        ExperienceStatus.APPROVED,
                        List.of(BookingMode.PRIVATE_ALLOWED, BookingMode.PRIVATE_ONLY),
                        AvailabilityStatus.AVAILABLE,
                        from,
                        to)
                .stream()
                .filter(slot -> slot.getBookedCount() == 0)
                .filter(slot -> slot.getCapacity() >= partySize)
                .filter(slot -> slot.getExperience().getPrivatePrice() != null)
                .filter(slot -> !slot.getExperience().isListedOnExternalPlatform())
                .filter(slot -> bookingWindowPolicy.isBookableAt(slot, now))
                .toList();
    }
}