package com.localbuddy.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AvailabilityScheduleRepository extends JpaRepository<AvailabilitySchedule, UUID> {

    List<AvailabilitySchedule> findByLocalProfileIdOrderByCreatedAtDesc(UUID localProfileId);

    List<AvailabilitySchedule> findByExperienceIdOrderByCreatedAtDesc(UUID experienceId);

    List<AvailabilitySchedule> findByStatus(ScheduleStatus status);
}
