package com.localbuddy.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    boolean existsByBookingId(UUID bookingId);

    boolean existsByBookingIdAndDirection(UUID bookingId, ReviewDirection direction);

    Optional<Review> findByBookingId(UUID bookingId);

    List<Review> findByLocalProfileIdAndDirectionAndStatusOrderByCreatedAtDesc(
            UUID localProfileId, ReviewDirection direction, ReviewStatus status);

    List<Review> findByExperienceIdAndDirectionAndStatusOrderByCreatedAtDesc(
            UUID experienceId, ReviewDirection direction, ReviewStatus status);

    List<Review> findByRevieweeUserIdAndDirectionAndStatusOrderByCreatedAtDesc(
            UUID revieweeUserId, ReviewDirection direction, ReviewStatus status);

    List<Review> findByRevieweeUserIdAndStatusOrderByCreatedAtDesc(
            UUID revieweeUserId, ReviewStatus status);

    List<Review> findByLocalProfileIdAndStatusOrderByCreatedAtDesc(
            UUID localProfileId,
            ReviewStatus status
    );

    List<Review> findByExperienceIdAndStatusOrderByCreatedAtDesc(
            UUID experienceId,
            ReviewStatus status
    );

    List<Review> findByReviewerUserIdOrderByCreatedAtDesc(UUID reviewerUserId);

    List<Review> findAllByOrderByCreatedAtDesc();

    long countByStatus(ReviewStatus status);
}