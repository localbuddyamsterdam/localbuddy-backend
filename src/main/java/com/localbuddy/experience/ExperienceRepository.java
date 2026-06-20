package com.localbuddy.experience;

import com.localbuddy.localprofile.LocalProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperienceRepository extends JpaRepository<Experience, UUID> {

    Optional<Experience> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Experience> findByLocalProfile(LocalProfile localProfile);

    List<Experience> findByLocalProfileId(UUID localProfileId);

    List<Experience> findByStatus(ExperienceStatus status);

    List<Experience> findByCity_SlugAndStatus(String citySlug, ExperienceStatus status);

    List<Experience> findByCity_SlugAndCategory_SlugAndStatus(
            String citySlug,
            String categorySlug,
            ExperienceStatus status
    );

    List<Experience> findByCategory_SlugAndStatus(
            String categorySlug,
            ExperienceStatus status
    );

    long countByStatus(ExperienceStatus status);

    @EntityGraph(attributePaths = {"localProfile", "localProfile.user"})
    Optional<Experience> findWithLocalProfileAndUserById(UUID id);

    /**
     * Search APPROVED experiences by optional city/category, age suitability, and
     * (when a date and/or guest count is supplied) the existence of a future
     * AVAILABLE slot on that date with enough remaining capacity.
     */
    @Query(value = """
            SELECT DISTINCT e FROM Experience e
            JOIN e.city c
            LEFT JOIN e.category cat
            WHERE e.status = com.localbuddy.experience.ExperienceStatus.APPROVED
              AND (:citySlug IS NULL OR c.slug = :citySlug)
              AND (:categorySlug IS NULL OR cat.slug = :categorySlug)
              AND (:maxMinimumAge IS NULL OR e.minimumAge <= :maxMinimumAge)
              AND (
                    (:guests IS NULL AND :dateStart IS NULL)
                    OR EXISTS (
                        SELECT 1 FROM AvailabilitySlot s
                        WHERE s.experience = e
                          AND s.status = com.localbuddy.availability.AvailabilityStatus.AVAILABLE
                          AND s.startTime > :now
                          AND (:guests IS NULL OR (s.capacity - s.bookedCount) >= :guests)
                          AND (:dateStart IS NULL OR (s.startTime >= :dateStart AND s.startTime < :dateEnd))
                    )
              )
            """,
            countQuery = """
            SELECT COUNT(DISTINCT e) FROM Experience e
            JOIN e.city c
            LEFT JOIN e.category cat
            WHERE e.status = com.localbuddy.experience.ExperienceStatus.APPROVED
              AND (:citySlug IS NULL OR c.slug = :citySlug)
              AND (:categorySlug IS NULL OR cat.slug = :categorySlug)
              AND (:maxMinimumAge IS NULL OR e.minimumAge <= :maxMinimumAge)
              AND (
                    (:guests IS NULL AND :dateStart IS NULL)
                    OR EXISTS (
                        SELECT 1 FROM AvailabilitySlot s
                        WHERE s.experience = e
                          AND s.status = com.localbuddy.availability.AvailabilityStatus.AVAILABLE
                          AND s.startTime > :now
                          AND (:guests IS NULL OR (s.capacity - s.bookedCount) >= :guests)
                          AND (:dateStart IS NULL OR (s.startTime >= :dateStart AND s.startTime < :dateEnd))
                    )
              )
            """)
    Page<Experience> searchApproved(
            @Param("citySlug") String citySlug,
            @Param("categorySlug") String categorySlug,
            @Param("maxMinimumAge") Integer maxMinimumAge,
            @Param("guests") Integer guests,
            @Param("now") Instant now,
            @Param("dateStart") Instant dateStart,
            @Param("dateEnd") Instant dateEnd,
            Pageable pageable
    );
}