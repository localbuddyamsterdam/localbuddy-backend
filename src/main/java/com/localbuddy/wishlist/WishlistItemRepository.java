package com.localbuddy.wishlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistItemRepository extends JpaRepository<WishlistItem, UUID> {

    List<WishlistItem> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<WishlistItem> findByUserIdAndExperienceId(UUID userId, UUID experienceId);

    boolean existsByUserIdAndExperienceId(UUID userId, UUID experienceId);
}
