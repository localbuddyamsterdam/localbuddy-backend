package com.localbuddy.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExperiencePhotoRepository extends JpaRepository<ExperiencePhoto, UUID> {

    List<ExperiencePhoto> findByExperienceIdOrderBySortOrderAscCreatedAtAsc(UUID experienceId);

    long countByExperienceId(UUID experienceId);
}
