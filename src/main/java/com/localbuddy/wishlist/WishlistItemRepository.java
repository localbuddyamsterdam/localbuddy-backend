package com.localbuddy.wishlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, UUID> {

    List<WishlistItem> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<WishlistItem> findByUserIdAndExperienceId(UUID userId, UUID experienceId);

    boolean existsByUserIdAndExperienceId(UUID userId, UUID experienceId);

    /** Lightweight list of the experience ids a user has wishlisted (for rendering hearts). */
    @Query("select w.experience.id from WishlistItem w where w.user.id = :userId")
    List<UUID> findExperienceIdsByUserId(UUID userId);

    /** Which of these experiences the user already has — used to skip duplicates on batch add. */
    @Query("select w.experience.id from WishlistItem w "
            + "where w.user.id = :userId and w.experience.id in :experienceIds")
    List<UUID> findExistingExperienceIds(UUID userId, Collection<UUID> experienceIds);

    /** How many people have saved an experience (social proof). */
    long countByExperienceId(UUID experienceId);

    /** Wishlist items added within a createdAt window — for the reminder sweep. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "experience"})
    List<WishlistItem> findTop200ByCreatedAtBetweenOrderByCreatedAtAsc(Instant from, Instant to);
}
