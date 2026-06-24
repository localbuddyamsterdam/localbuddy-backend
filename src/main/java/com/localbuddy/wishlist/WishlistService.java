package com.localbuddy.wishlist;

import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WishlistService {

    private final WishlistItemRepository wishlistItemRepository;
    private final ExperienceRepository experienceRepository;
    private final UserRepository userRepository;

    public WishlistService(WishlistItemRepository wishlistItemRepository,
                           ExperienceRepository experienceRepository,
                           UserRepository userRepository) {
        this.wishlistItemRepository = wishlistItemRepository;
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> getMyWishlist(UUID userId) {
        return wishlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(WishlistItemResponse::from).toList();
    }

    @Transactional
    public WishlistItemResponse addToWishlist(UUID userId, UUID experienceId) {
        WishlistItem existing = wishlistItemRepository.findByUserIdAndExperienceId(userId, experienceId).orElse(null);
        if (existing != null) {
            return WishlistItemResponse.from(existing);
        }

        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new ResourceNotFoundException("Experience not found");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        WishlistItem item = new WishlistItem();
        item.setUser(user);
        item.setExperience(experience);
        return WishlistItemResponse.from(wishlistItemRepository.save(item));
    }

    @Transactional
    public void removeFromWishlist(UUID userId, UUID experienceId) {
        wishlistItemRepository.findByUserIdAndExperienceId(userId, experienceId)
                .ifPresent(wishlistItemRepository::delete);
    }

    @Transactional(readOnly = true)
    public boolean isInWishlist(UUID userId, UUID experienceId) {
        return wishlistItemRepository.existsByUserIdAndExperienceId(userId, experienceId);
    }

    /** Just the experience ids the user has wishlisted — for rendering hearts across listings. */
    @Transactional(readOnly = true)
    public List<UUID> getMyWishlistExperienceIds(UUID userId) {
        return wishlistItemRepository.findExperienceIdsByUserId(userId);
    }

    /**
     * Adds several experiences at once (e.g. merging a guest's locally-saved favourites after login).
     * Idempotent: ids that are unknown, not APPROVED, or already saved are skipped. Returns the
     * resulting wishlist.
     */
    @Transactional
    public List<WishlistItemResponse> addBatch(UUID userId, List<UUID> experienceIds) {
        List<UUID> requested = experienceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return getMyWishlist(userId);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Set<UUID> alreadySaved = new HashSet<>(
                wishlistItemRepository.findExistingExperienceIds(userId, requested));

        List<WishlistItem> toSave = experienceRepository.findAllById(requested).stream()
                .filter(e -> e.getStatus() == ExperienceStatus.APPROVED)
                .filter(e -> !alreadySaved.contains(e.getId()))
                .map(e -> {
                    WishlistItem item = new WishlistItem();
                    item.setUser(user);
                    item.setExperience(e);
                    return item;
                })
                .toList();

        if (!toSave.isEmpty()) {
            wishlistItemRepository.saveAll(toSave);
        }
        return getMyWishlist(userId);
    }
}
