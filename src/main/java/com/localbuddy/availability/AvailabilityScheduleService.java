package com.localbuddy.availability;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.City;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns recurring availability schedules (Option B): the host edits one pattern
 * object; this service materialises it into concrete {@link AvailabilitySlot}
 * rows up to a rolling horizon, honouring the {@link SchedulingPolicy} travel gap
 * so a host is never double-booked across experiences.
 */
@Service
public class AvailabilityScheduleService {

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Amsterdam");

    private final AvailabilityScheduleRepository scheduleRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final ExperienceRepository experienceRepository;
    private final LocalProfileRepository localProfileRepository;
    private final SchedulingPolicy schedulingPolicy;
    private final int horizonDays;

    public AvailabilityScheduleService(AvailabilityScheduleRepository scheduleRepository,
                                       AvailabilitySlotRepository availabilitySlotRepository,
                                       ExperienceRepository experienceRepository,
                                       LocalProfileRepository localProfileRepository,
                                       SchedulingPolicy schedulingPolicy,
                                       @Value("${app.availability.materialization-horizon-days:90}") int horizonDays) {
        this.scheduleRepository = scheduleRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.experienceRepository = experienceRepository;
        this.localProfileRepository = localProfileRepository;
        this.schedulingPolicy = schedulingPolicy;
        this.horizonDays = horizonDays;
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public ScheduleResponse createSchedule(UUID userId, CreateScheduleRequest request) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        Experience experience = loadOwnedApprovedExperience(localProfile, request.experienceId());

        validateDateRange(request.startDate(), request.endDate());
        validateCapacity(request.capacity(), experience);
        Map<DayOfWeek, List<LocalTime>> pattern = buildPattern(request.weekly());
        validatePatternInternalConsistency(pattern, experience.getDurationMinutes());

        AvailabilitySchedule schedule = new AvailabilitySchedule();
        schedule.setExperience(experience);
        schedule.setLocalProfile(localProfile);
        schedule.setStartDate(request.startDate());
        schedule.setEndDate(request.endDate());
        schedule.setCapacity(request.capacity());
        schedule.setPrivateEligible(request.privateEligible());
        schedule.setTimezone(resolveZone(experience).getId());
        schedule.setStatus(ScheduleStatus.ACTIVE);
        schedule.setTimes(toScheduleTimes(pattern));
        schedule = scheduleRepository.save(schedule);

        materializeInternal(schedule);
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse updateSchedule(UUID userId, UUID scheduleId, UpdateScheduleRequest request) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySchedule schedule = loadOwnedSchedule(localProfile, scheduleId);
        Experience experience = schedule.getExperience();

        validateDateRange(request.startDate(), request.endDate());
        validateCapacity(request.capacity(), experience);
        Map<DayOfWeek, List<LocalTime>> pattern = buildPattern(request.weekly());
        validatePatternInternalConsistency(pattern, experience.getDurationMinutes());

        // Regenerate: drop this schedule's future UNBOOKED slots, keep booked ones.
        deleteFutureUnbookedSlots(schedule.getId(), Instant.now());

        schedule.setStartDate(request.startDate());
        schedule.setEndDate(request.endDate());
        schedule.setCapacity(request.capacity());
        schedule.setPrivateEligible(request.privateEligible());
        schedule.setTimes(toScheduleTimes(pattern));
        schedule.setMaterializedUntil(null);
        scheduleRepository.save(schedule);

        materializeInternal(schedule);
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse pauseSchedule(UUID userId, UUID scheduleId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySchedule schedule = loadOwnedSchedule(localProfile, scheduleId);

        schedule.setStatus(ScheduleStatus.PAUSED);
        scheduleRepository.save(schedule);

        // Take future unbooked slots off the market while paused.
        for (AvailabilitySlot slot : availabilitySlotRepository
                .findBySourceScheduleIdAndStartTimeAfter(scheduleId, Instant.now())) {
            if (slot.getStatus() == AvailabilityStatus.AVAILABLE && bookedCount(slot) == 0) {
                slot.setStatus(AvailabilityStatus.BLOCKED);
                availabilitySlotRepository.save(slot);
            }
        }
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse resumeSchedule(UUID userId, UUID scheduleId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySchedule schedule = loadOwnedSchedule(localProfile, scheduleId);

        schedule.setStatus(ScheduleStatus.ACTIVE);
        scheduleRepository.save(schedule);

        for (AvailabilitySlot slot : availabilitySlotRepository
                .findBySourceScheduleIdAndStartTimeAfter(scheduleId, Instant.now())) {
            if (slot.getStatus() == AvailabilityStatus.BLOCKED && bookedCount(slot) == 0) {
                slot.setStatus(AvailabilityStatus.AVAILABLE);
                availabilitySlotRepository.save(slot);
            }
        }
        materializeInternal(schedule);
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse extendSchedule(UUID userId, UUID scheduleId, ExtendScheduleRequest request) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySchedule schedule = loadOwnedSchedule(localProfile, scheduleId);

        if (request.endDate().isBefore(schedule.getStartDate())) {
            throw new BadRequestException("End date must be on or after the start date");
        }

        boolean shrinking = request.endDate().isBefore(schedule.getEndDate());
        schedule.setEndDate(request.endDate());

        if (shrinking) {
            // Remove future unbooked slots beyond the new end date.
            ZoneId zone = safeZone(schedule.getTimezone());
            Instant cutoff = request.endDate().plusDays(1).atStartOfDay(zone).toInstant();
            for (AvailabilitySlot slot : availabilitySlotRepository
                    .findBySourceScheduleIdAndStartTimeAfter(scheduleId, Instant.now())) {
                if (!slot.getStartTime().isBefore(cutoff) && bookedCount(slot) == 0
                        && slot.getStatus() != AvailabilityStatus.CANCELLED) {
                    availabilitySlotRepository.delete(slot);
                }
            }
            if (schedule.getMaterializedUntil() != null && schedule.getMaterializedUntil().isAfter(request.endDate())) {
                schedule.setMaterializedUntil(request.endDate());
            }
        }
        scheduleRepository.save(schedule);

        materializeInternal(schedule);
        return toResponse(schedule);
    }

    @Transactional
    public void deleteSchedule(UUID userId, UUID scheduleId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        AvailabilitySchedule schedule = loadOwnedSchedule(localProfile, scheduleId);

        List<AvailabilitySlot> future = availabilitySlotRepository
                .findBySourceScheduleIdAndStartTimeAfter(scheduleId, Instant.now());
        boolean hasBooked = future.stream().anyMatch(s -> bookedCount(s) > 0);
        if (hasBooked) {
            throw new BadRequestException(
                    "This schedule has upcoming booked sessions. Cancel or let those run before deleting the schedule.");
        }
        availabilitySlotRepository.deleteAll(future);
        // Past slots keep their history; the FK clears their source_schedule_id on delete.
        scheduleRepository.delete(schedule);
    }

    // -------------------------------------------------------------- validation

    @Transactional(readOnly = true)
    public ValidateAvailabilityResponse validate(UUID userId, ValidateAvailabilityRequest request) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        Experience experience = loadOwnedApprovedExperience(localProfile, request.experienceId());
        Duration duration = Duration.ofMinutes(experience.getDurationMinutes());

        List<AvailabilitySlot> existing = availabilitySlotRepository
                .findByLocalProfileIdAndStartTimeGreaterThanEqualAndStatusNot(
                        localProfile.getId(), Instant.now(), AvailabilityStatus.CANCELLED)
                .stream()
                .filter(this::occupiesHostTime)
                .toList();

        List<Instant> proposed = request.starts().stream().distinct().sorted().toList();
        List<ValidateAvailabilityResponse.Conflict> conflicts = new ArrayList<>();
        List<Instant[]> accepted = new ArrayList<>();

        for (Instant start : proposed) {
            Instant end = start.plus(duration);
            ValidateAvailabilityResponse.Conflict conflict = null;

            for (AvailabilitySlot slot : existing) {
                boolean sameSession = slot.getStartTime().equals(start)
                        && slot.getExperience().getId().equals(experience.getId());
                if (sameSession) {
                    continue; // re-validating an existing session against itself
                }
                if (schedulingPolicy.conflicts(experience.getId(), start, end,
                        slot.getExperience().getId(), slot.getStartTime(), slot.getEndTime())) {
                    boolean sameExp = slot.getExperience().getId().equals(experience.getId());
                    conflict = new ValidateAvailabilityResponse.Conflict(
                            start, end, slot.getId(), slot.getExperience().getId(),
                            slot.getExperience().getTitle(), slot.getStartTime(), slot.getEndTime(),
                            sameExp, conflictMessage(sameExp, slot.getExperience().getTitle()));
                    break;
                }
            }

            if (conflict == null) {
                for (Instant[] p : accepted) {
                    if (schedulingPolicy.conflicts(experience.getId(), start, end,
                            experience.getId(), p[0], p[1])) {
                        conflict = new ValidateAvailabilityResponse.Conflict(
                                start, end, null, experience.getId(), experience.getTitle(),
                                p[0], p[1], true, "Overlaps another session you're adding");
                        break;
                    }
                }
            }

            if (conflict != null) {
                conflicts.add(conflict);
            } else {
                accepted.add(new Instant[]{start, end});
            }
        }
        return new ValidateAvailabilityResponse(conflicts);
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getMySchedules(UUID userId, UUID experienceId) {
        LocalProfile localProfile = getApprovedLocalProfile(userId);
        List<AvailabilitySchedule> schedules;
        if (experienceId != null) {
            schedules = scheduleRepository.findByExperienceIdOrderByCreatedAtDesc(experienceId).stream()
                    .filter(s -> s.getLocalProfile().getId().equals(localProfile.getId()))
                    .toList();
        } else {
            schedules = scheduleRepository.findByLocalProfileIdOrderByCreatedAtDesc(localProfile.getId());
        }
        return schedules.stream().map(this::toResponse).toList();
    }

    // ----------------------------------------------------------- materialization

    /** Public, transaction-per-schedule entry point used by the scheduled job. */
    @Transactional
    public int materialize(UUID scheduleId) {
        AvailabilitySchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            return 0;
        }
        return materializeInternal(schedule);
    }

    private int materializeInternal(AvailabilitySchedule schedule) {
        if (schedule.getStatus() != ScheduleStatus.ACTIVE) {
            return 0;
        }
        ZoneId zone = safeZone(schedule.getTimezone());
        Experience experience = schedule.getExperience();
        int durationMinutes = experience.getDurationMinutes();

        LocalDate today = LocalDate.now(zone);
        LocalDate from = schedule.getStartDate();
        if (schedule.getMaterializedUntil() != null) {
            LocalDate nextFrom = schedule.getMaterializedUntil().plusDays(1);
            if (nextFrom.isAfter(from)) {
                from = nextFrom;
            }
        }
        if (from.isBefore(today)) {
            from = today;
        }
        LocalDate horizonEnd = today.plusDays(horizonDays);
        LocalDate to = schedule.getEndDate().isBefore(horizonEnd) ? schedule.getEndDate() : horizonEnd;

        if (to.isBefore(from)) {
            advanceMaterializedUntil(schedule, to);
            return 0;
        }

        Map<DayOfWeek, List<LocalTime>> pattern = toPattern(schedule.getTimes());
        Instant windowFrom = from.atStartOfDay(zone).toInstant();
        Instant windowTo = to.plusDays(1).atStartOfDay(zone).toInstant();

        Set<Instant> existingStarts = availabilitySlotRepository
                .findByExperienceIdAndStartTimeBetween(experience.getId(), windowFrom, windowTo)
                .stream().map(AvailabilitySlot::getStartTime).collect(Collectors.toCollection(HashSet::new));

        List<OccupiedInterval> occupied = loadHostOccupied(schedule.getLocalProfile().getId(), windowFrom);

        Instant now = Instant.now();
        List<AvailabilitySlot> toCreate = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<LocalTime> times = pattern.get(date.getDayOfWeek());
            if (times == null) {
                continue;
            }
            for (LocalTime time : times) {
                Instant start = date.atTime(time).atZone(zone).toInstant();
                Instant end = start.plus(Duration.ofMinutes(durationMinutes));
                if (!start.isAfter(now)) {
                    continue;
                }
                if (existingStarts.contains(start)) {
                    continue;
                }
                if (conflictsWithOccupied(experience.getId(), start, end, occupied)) {
                    continue;
                }
                AvailabilitySlot slot = new AvailabilitySlot();
                slot.setExperience(experience);
                slot.setLocalProfile(schedule.getLocalProfile());
                slot.setStartTime(start);
                slot.setEndTime(end);
                slot.setCapacity(schedule.getCapacity());
                slot.setBookedCount(0);
                slot.setStatus(AvailabilityStatus.AVAILABLE);
                slot.setSourceSchedule(schedule);
                slot.setPrivateEligible(schedule.isPrivateEligible());
                toCreate.add(slot);
                existingStarts.add(start);
                occupied.add(new OccupiedInterval(experience.getId(), start, end));
            }
        }
        if (!toCreate.isEmpty()) {
            availabilitySlotRepository.saveAll(toCreate);
        }
        advanceMaterializedUntil(schedule, to);
        return toCreate.size();
    }

    private void advanceMaterializedUntil(AvailabilitySchedule schedule, LocalDate to) {
        if (schedule.getMaterializedUntil() == null || to.isAfter(schedule.getMaterializedUntil())) {
            schedule.setMaterializedUntil(to);
            scheduleRepository.save(schedule);
        }
    }

    private boolean conflictsWithOccupied(UUID experienceId, Instant start, Instant end, List<OccupiedInterval> occupied) {
        for (OccupiedInterval o : occupied) {
            if (schedulingPolicy.conflicts(experienceId, start, end, o.experienceId(), o.start(), o.end())) {
                return true;
            }
        }
        return false;
    }

    private List<OccupiedInterval> loadHostOccupied(UUID localProfileId, Instant from) {
        return availabilitySlotRepository
                .findByLocalProfileIdAndStartTimeGreaterThanEqualAndStatusNot(
                        localProfileId, from, AvailabilityStatus.CANCELLED)
                .stream()
                .filter(this::occupiesHostTime)
                .map(s -> new OccupiedInterval(s.getExperience().getId(), s.getStartTime(), s.getEndTime()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** A slot reserves the host's time if it's open for bookings or already has some (a manually blocked, empty slot doesn't). */
    private boolean occupiesHostTime(AvailabilitySlot slot) {
        return slot.getStatus() == AvailabilityStatus.AVAILABLE || bookedCount(slot) > 0;
    }

    // ----------------------------------------------------------------- helpers

    private void deleteFutureUnbookedSlots(UUID scheduleId, Instant from) {
        for (AvailabilitySlot slot : availabilitySlotRepository.findBySourceScheduleIdAndStartTimeAfter(scheduleId, from)) {
            if (bookedCount(slot) == 0) {
                availabilitySlotRepository.delete(slot);
            }
        }
    }

    private int bookedCount(AvailabilitySlot slot) {
        return slot.getBookedCount() == null ? 0 : slot.getBookedCount();
    }

    private void validateDateRange(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new BadRequestException("End date must be on or after the start date");
        }
    }

    private void validateCapacity(int capacity, Experience experience) {
        if (capacity < 1) {
            throw new BadRequestException("Capacity must be at least 1");
        }
        Integer max = experience.getMaxGuests();
        if (max != null && capacity > max) {
            throw new BadRequestException("Capacity cannot exceed the experience's max guests (" + max + ")");
        }
    }

    private Map<DayOfWeek, List<LocalTime>> buildPattern(List<CreateScheduleRequest.WeeklyRule> weekly) {
        Map<DayOfWeek, List<LocalTime>> pattern = new EnumMap<>(DayOfWeek.class);
        for (CreateScheduleRequest.WeeklyRule rule : weekly) {
            if (pattern.containsKey(rule.dayOfWeek())) {
                throw new BadRequestException("Each day of the week may appear only once");
            }
            List<LocalTime> times = rule.times().stream()
                    .map(String::trim).distinct().map(LocalTime::parse).sorted().toList();
            pattern.put(rule.dayOfWeek(), times);
        }
        return pattern;
    }

    private void validatePatternInternalConsistency(Map<DayOfWeek, List<LocalTime>> pattern, int durationMinutes) {
        Duration duration = Duration.ofMinutes(durationMinutes);
        for (Map.Entry<DayOfWeek, List<LocalTime>> entry : pattern.entrySet()) {
            List<LocalTime> times = entry.getValue();
            for (int i = 1; i < times.size(); i++) {
                LocalTime previousEnd = times.get(i - 1).plus(duration);
                if (times.get(i).isBefore(previousEnd)) {
                    throw new BadRequestException("On " + entry.getKey() + ", the " + times.get(i)
                            + " session overlaps the " + times.get(i - 1) + " one (" + durationMinutes + " min each)");
                }
            }
        }
    }

    private List<ScheduleTime> toScheduleTimes(Map<DayOfWeek, List<LocalTime>> pattern) {
        List<ScheduleTime> result = new ArrayList<>();
        for (Map.Entry<DayOfWeek, List<LocalTime>> entry : pattern.entrySet()) {
            for (LocalTime time : entry.getValue()) {
                result.add(new ScheduleTime(entry.getKey(), time));
            }
        }
        return result;
    }

    private Map<DayOfWeek, List<LocalTime>> toPattern(List<ScheduleTime> times) {
        Map<DayOfWeek, List<LocalTime>> pattern = new EnumMap<>(DayOfWeek.class);
        for (ScheduleTime time : times) {
            pattern.computeIfAbsent(time.getDayOfWeek(), k -> new ArrayList<>()).add(time.getStartTime());
        }
        pattern.values().forEach(list -> list.sort(LocalTime::compareTo));
        return pattern;
    }

    private String conflictMessage(boolean sameExperience, String otherTitle) {
        if (sameExperience) {
            return "Overlaps another session of this experience";
        }
        long gap = schedulingPolicy.travelGapMinutes() + schedulingPolicy.arrivalLeadMinutes();
        return "Too close to \"" + otherTitle + "\" — you need " + gap + " min to travel and be on-site";
    }

    private LocalProfile getApprovedLocalProfile(UUID userId) {
        LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        if (localProfile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Local profile must be approved");
        }
        return localProfile;
    }

    private Experience loadOwnedApprovedExperience(LocalProfile localProfile, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new BadRequestException("Invalid experience"));
        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new BadRequestException("You can manage availability only for your own experience");
        }
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Experience must be approved before adding availability");
        }
        return experience;
    }

    private AvailabilitySchedule loadOwnedSchedule(LocalProfile localProfile, UUID scheduleId) {
        AvailabilitySchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found"));
        if (!schedule.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Schedule not found");
        }
        return schedule;
    }

    private ZoneId resolveZone(Experience experience) {
        City city = experience.getCity();
        if (city != null && city.getTimezone() != null && !city.getTimezone().isBlank()) {
            try {
                return ZoneId.of(city.getTimezone());
            } catch (Exception ignored) {
                // fall through
            }
        }
        return FALLBACK_ZONE;
    }

    private ZoneId safeZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ex) {
            return FALLBACK_ZONE;
        }
    }

    private ScheduleResponse toResponse(AvailabilitySchedule schedule) {
        Map<DayOfWeek, List<LocalTime>> pattern = toPattern(schedule.getTimes());
        List<ScheduleResponse.WeeklyRuleResponse> weekly = pattern.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ScheduleResponse.WeeklyRuleResponse(
                        e.getKey(),
                        e.getValue().stream().map(LocalTime::toString).toList()))
                .toList();

        return new ScheduleResponse(
                schedule.getId(),
                schedule.getExperience().getId(),
                schedule.getLocalProfile().getId(),
                schedule.getStartDate(),
                schedule.getEndDate(),
                schedule.getCapacity(),
                schedule.isPrivateEligible(),
                schedule.getTimezone(),
                schedule.getStatus(),
                schedule.getMaterializedUntil(),
                weekly,
                schedule.getCreatedAt(),
                schedule.getUpdatedAt()
        );
    }

    /** A host session that reserves wall-clock time, used for cross-experience conflict checks. */
    private record OccupiedInterval(UUID experienceId, Instant start, Instant end) {
    }
}
