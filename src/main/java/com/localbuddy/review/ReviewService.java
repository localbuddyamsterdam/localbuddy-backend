package com.localbuddy.review;

import com.localbuddy.booking.Booking;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final LocalProfileRepository localProfileRepository;

    public ReviewService(ReviewRepository reviewRepository,
                         BookingRepository bookingRepository,
                         UserRepository userRepository,
                         LocalProfileRepository localProfileRepository) {
        this.reviewRepository = reviewRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.localProfileRepository = localProfileRepository;
    }

    @Transactional
    public ReviewResponse createReview(UUID reviewerUserId, CreateReviewRequest request) {
        User reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        if (reviewer.getRole() != UserRole.LOGGED_IN_USER) {
            throw new BadRequestException("Only travelers can create reviews");
        }

        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getLoggedInUser() == null ||
                !booking.getLoggedInUser().getId().equals(reviewerUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (reviewRepository.existsByBookingIdAndDirection(booking.getId(), ReviewDirection.TRAVELER_TO_HOST)) {
            throw new BadRequestException("Review already exists for this booking");
        }

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("Review is allowed only after the booking is completed");
        }

        Review review = new Review();
        review.setBooking(booking);
        review.setDirection(ReviewDirection.TRAVELER_TO_HOST);
        review.setReviewerUser(reviewer);
        review.setRevieweeUser(booking.getLocalProfile().getUser());
        review.setLocalProfile(booking.getLocalProfile());
        review.setExperience(booking.getExperience());
        review.setRating(request.rating());
        review.setComment(optionalTrim(request.comment()));
        review.setStatus(ReviewStatus.VISIBLE);

        Review saved = reviewRepository.save(review);
        recomputeHostRating(booking.getLocalProfile());
        return toResponse(saved);
    }

    /** A host reviews the traveler of one of their completed bookings. */
    @Transactional
    public ReviewResponse createTravelerReview(UUID hostUserId, CreateReviewRequest request) {
        User host = userRepository.findById(hostUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (booking.getLocalProfile().getUser() == null ||
                !booking.getLocalProfile().getUser().getId().equals(hostUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }

        if (booking.getLoggedInUser() == null) {
            throw new BadRequestException("Guest bookings cannot be reviewed");
        }

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("Review is allowed only after the booking is completed");
        }

        if (reviewRepository.existsByBookingIdAndDirection(booking.getId(), ReviewDirection.HOST_TO_TRAVELER)) {
            throw new BadRequestException("You have already reviewed this traveler for this booking");
        }

        Review review = new Review();
        review.setBooking(booking);
        review.setDirection(ReviewDirection.HOST_TO_TRAVELER);
        review.setReviewerUser(host);
        review.setRevieweeUser(booking.getLoggedInUser());
        review.setLocalProfile(booking.getLocalProfile());
        review.setExperience(booking.getExperience());
        review.setRating(request.rating());
        review.setComment(optionalTrim(request.comment()));
        review.setStatus(ReviewStatus.VISIBLE);

        Review saved = reviewRepository.save(review);
        recomputeTravelerRating(booking.getLoggedInUser());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getMyReviews(UUID reviewerUserId) {
        return reviewRepository.findByReviewerUserIdOrderByCreatedAtDesc(reviewerUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getPublicReviewsForLocalProfile(UUID localProfileId) {
        return reviewRepository.findByLocalProfileIdAndDirectionAndStatusOrderByCreatedAtDesc(
                        localProfileId, ReviewDirection.TRAVELER_TO_HOST, ReviewStatus.VISIBLE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getPublicReviewsForExperience(UUID experienceId) {
        return reviewRepository.findByExperienceIdAndDirectionAndStatusOrderByCreatedAtDesc(
                        experienceId, ReviewDirection.TRAVELER_TO_HOST, ReviewStatus.VISIBLE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** Visible reviews written about the given user (either direction). */
    @Transactional(readOnly = true)
    public List<ReviewResponse> getReceivedReviews(UUID userId) {
        return reviewRepository.findByRevieweeUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewStatus.VISIBLE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ReviewResponse hideReview(UUID reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        review.setStatus(ReviewStatus.HIDDEN);
        review.setModerationReason(optionalTrim(reason));
        review.setModeratedAt(java.time.Instant.now());

        Review saved = reviewRepository.save(review);
        recomputeForReview(saved);
        return toResponse(saved);
    }

    @Transactional
    public ReviewResponse unhideReview(UUID reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        review.setStatus(ReviewStatus.VISIBLE);
        review.setModerationReason(optionalTrim(reason));
        review.setModeratedAt(java.time.Instant.now());

        Review saved = reviewRepository.save(review);
        recomputeForReview(saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getAdminReviews() {
        return reviewRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private void recomputeForReview(Review review) {
        if (review.getDirection() == ReviewDirection.HOST_TO_TRAVELER) {
            if (review.getRevieweeUser() != null) {
                recomputeTravelerRating(review.getRevieweeUser());
            }
        } else {
            recomputeHostRating(review.getLocalProfile());
        }
    }

    private void recomputeHostRating(LocalProfile localProfile) {
        List<Review> visible = reviewRepository.findByLocalProfileIdAndDirectionAndStatusOrderByCreatedAtDesc(
                localProfile.getId(), ReviewDirection.TRAVELER_TO_HOST, ReviewStatus.VISIBLE);
        localProfile.setRatingAvg(averageRating(visible));
        localProfile.setTotalReviews(visible.size());
        localProfileRepository.save(localProfile);
    }

    private void recomputeTravelerRating(User traveler) {
        List<Review> visible = reviewRepository.findByRevieweeUserIdAndDirectionAndStatusOrderByCreatedAtDesc(
                traveler.getId(), ReviewDirection.HOST_TO_TRAVELER, ReviewStatus.VISIBLE);
        traveler.setRatingAvg(averageRating(visible));
        traveler.setTotalReviews(visible.size());
        userRepository.save(traveler);
    }

    private BigDecimal averageRating(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        int sum = reviews.stream().mapToInt(Review::getRating).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP);
    }

    private ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getBooking().getId(),
                review.getDirection(),
                review.getReviewerUser() != null ? review.getReviewerUser().getId() : null,
                review.getRevieweeUser() != null ? review.getRevieweeUser().getId() : null,
                review.getLocalProfile().getId(),
                review.getExperience().getId(),
                review.getRating(),
                review.getComment(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}