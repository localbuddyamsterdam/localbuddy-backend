package com.localbuddy.experience;

import com.localbuddy.localprofile.LocalProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
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

    /**
     * Public listing of APPROVED experiences with optional city/category and
     * booking-mode filters. A null {@code bookingModes} collection means "any mode";
     * otherwise only experiences whose {@code bookingMode} is in the set are returned.
     */
    @Query("""
            SELECT e FROM Experience e
            JOIN e.city c
            LEFT JOIN e.category cat
            WHERE e.status = com.localbuddy.experience.ExperienceStatus.APPROVED
              AND (:citySlug IS NULL OR c.slug = :citySlug)
              AND (:categorySlug IS NULL OR cat.slug = :categorySlug)
              AND (:bookingModes IS NULL OR e.bookingMode IN :bookingModes)
            ORDER BY e.createdAt DESC
            """)
    List<Experience> findApprovedForListing(
            @Param("citySlug") String citySlug,
            @Param("categorySlug") String categorySlug,
            @Param("bookingModes") Collection<BookingMode> bookingModes
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

    /**
     * Advanced search over APPROVED experiences: all of {@link #searchApproved}'s
     * filters plus optional price range, minimum host rating, maximum duration, and
     * a keyword matched against title/description. The {@code keyword} parameter must
     * already be lower-cased and wrapped in {@code %...%} (or null).
     */
    @Query(value = """
            SELECT DISTINCT e FROM Experience e
            JOIN e.city c
            LEFT JOIN e.category cat
            JOIN e.localProfile lp
            LEFT JOIN lp.user u
            WHERE e.status = com.localbuddy.experience.ExperienceStatus.APPROVED
              AND (:citySlug IS NULL OR c.slug = :citySlug)
              AND (:categorySlug IS NULL OR cat.slug = :categorySlug)
              AND (:maxMinimumAge IS NULL OR e.minimumAge <= :maxMinimumAge)
              AND (:minPrice IS NULL OR e.priceAmount >= :minPrice)
              AND (:maxPrice IS NULL OR e.priceAmount <= :maxPrice)
              AND (:maxDurationMinutes IS NULL OR e.durationMinutes <= :maxDurationMinutes)
              AND (:minHostRating IS NULL OR (u IS NOT NULL AND u.ratingAvg >= :minHostRating))
              AND (:keyword IS NULL OR lower(e.title) LIKE :keyword OR lower(e.description) LIKE :keyword)
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
            JOIN e.localProfile lp
            LEFT JOIN lp.user u
            WHERE e.status = com.localbuddy.experience.ExperienceStatus.APPROVED
              AND (:citySlug IS NULL OR c.slug = :citySlug)
              AND (:categorySlug IS NULL OR cat.slug = :categorySlug)
              AND (:maxMinimumAge IS NULL OR e.minimumAge <= :maxMinimumAge)
              AND (:minPrice IS NULL OR e.priceAmount >= :minPrice)
              AND (:maxPrice IS NULL OR e.priceAmount <= :maxPrice)
              AND (:maxDurationMinutes IS NULL OR e.durationMinutes <= :maxDurationMinutes)
              AND (:minHostRating IS NULL OR (u IS NOT NULL AND u.ratingAvg >= :minHostRating))
              AND (:keyword IS NULL OR lower(e.title) LIKE :keyword OR lower(e.description) LIKE :keyword)
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
    Page<Experience> searchApprovedAdvanced(
            @Param("citySlug") String citySlug,
            @Param("categorySlug") String categorySlug,
            @Param("maxMinimumAge") Integer maxMinimumAge,
            @Param("guests") Integer guests,
            @Param("now") Instant now,
            @Param("dateStart") Instant dateStart,
            @Param("dateEnd") Instant dateEnd,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("maxDurationMinutes") Integer maxDurationMinutes,
            @Param("minHostRating") BigDecimal minHostRating,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}