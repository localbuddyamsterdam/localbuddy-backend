package com.localbuddy.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperiencePhotoRepository extends JpaRepository<ExperiencePhoto, UUID> {

    List<ExperiencePhoto> findByExperienceIdOrderBySortOrderAscCreatedAtAsc(UUID experienceId);

    long countByExperienceId(UUID experienceId);

    @Query("SELECT p FROM ExperiencePhoto p WHERE p.experience.id = :experienceId AND p.cover = true")
    Optional<ExperiencePhoto> findCoverPhotoByExperienceId(@Param("experienceId") UUID experienceId);

    /** Cover photos for a set of experiences in one query — lets list mapping avoid a per-row cover N+1. */
    @Query("SELECT p FROM ExperiencePhoto p WHERE p.experience.id IN :ids AND p.cover = true")
    List<ExperiencePhoto> findCoverPhotosByExperienceIds(@Param("ids") List<UUID> ids);
}
