package com.localbuddy.availability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/availability/schedules")
@Tag(name = "Availability schedules", description = "Recurring weekly availability patterns for a local's experiences")
@SecurityRequirement(name = "bearerAuth")
public class AvailabilityScheduleController {

    private final AvailabilityScheduleService scheduleService;

    public AvailabilityScheduleController(AvailabilityScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "Create a recurring availability schedule")
    @PostMapping
    public ResponseEntity<ScheduleResponse> create(Authentication authentication,
                                                   @Valid @RequestBody CreateScheduleRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleService.createSchedule(userId, request));
    }

    @Operation(summary = "List my schedules (optionally filtered to one experience)")
    @GetMapping
    public ResponseEntity<List<ScheduleResponse>> list(Authentication authentication,
                                                       @RequestParam(required = false) UUID experienceId) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.getMySchedules(userId, experienceId));
    }

    @Operation(summary = "Update a schedule's weekly pattern, dates, or capacity")
    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponse> update(Authentication authentication,
                                                   @PathVariable UUID scheduleId,
                                                   @Valid @RequestBody UpdateScheduleRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.updateSchedule(userId, scheduleId, request));
    }

    @Operation(summary = "Pause a schedule (stops new slots and blocks future unbooked ones)")
    @PostMapping("/{scheduleId}/pause")
    public ResponseEntity<ScheduleResponse> pause(Authentication authentication, @PathVariable UUID scheduleId) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.pauseSchedule(userId, scheduleId));
    }

    @Operation(summary = "Resume a paused schedule")
    @PostMapping("/{scheduleId}/resume")
    public ResponseEntity<ScheduleResponse> resume(Authentication authentication, @PathVariable UUID scheduleId) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.resumeSchedule(userId, scheduleId));
    }

    @Operation(summary = "Extend or shrink a schedule's end date")
    @PostMapping("/{scheduleId}/extend")
    public ResponseEntity<ScheduleResponse> extend(Authentication authentication,
                                                   @PathVariable UUID scheduleId,
                                                   @Valid @RequestBody ExtendScheduleRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.extendSchedule(userId, scheduleId, request));
    }

    @Operation(summary = "Delete a schedule (rejected if it has upcoming booked sessions)")
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID scheduleId) {
        UUID userId = UUID.fromString(authentication.getName());
        scheduleService.deleteSchedule(userId, scheduleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Dry-run: check proposed session start times for travel-gap/overlap conflicts")
    @PostMapping("/validate")
    public ResponseEntity<ValidateAvailabilityResponse> validate(Authentication authentication,
                                                                 @Valid @RequestBody ValidateAvailabilityRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(scheduleService.validate(userId, request));
    }
}
