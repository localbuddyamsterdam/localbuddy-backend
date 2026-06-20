package com.localbuddy.gdpr;

import com.localbuddy.review.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read-only view over reviews authored by the user for GDPR export. */
public interface GdprReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByReviewerUserIdOrderByCreatedAtDesc(UUID reviewerUserId);
}
