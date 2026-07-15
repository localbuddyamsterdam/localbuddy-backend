package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.experience.Experience;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.promo.PromoCode;
import com.localbuddy.referral.ReferralCode;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "booking_reference", nullable = false, unique = true, length = 40)
    private String bookingReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "traveler_user_id")
    private User loggedInUser;

    @Column(name = "guest_first_name", length = 100)
    private String guestFirstName;

    @Column(name = "guest_last_name", length = 100)
    private String guestLastName;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Column(name = "guest_phone", length = 40)
    private String guestPhone;

    @Column(name = "guest_email_verified", nullable = false)
    private boolean guestEmailVerified = false;

    @Column(name = "guest_phone_verified", nullable = false)
    private boolean guestPhoneVerified = false;

    // Emergency-contact snapshot captured at checkout. Optional (all nullable); the
    // "all-or-nothing" rule is enforced in BookingService. Frozen at booking time —
    // it does NOT track later edits to the traveller's profile emergency contact.
    @Column(name = "emergency_contact_first_name", length = 100)
    private String emergencyContactFirstName;

    @Column(name = "emergency_contact_last_name", length = 100)
    private String emergencyContactLastName;

    @Column(name = "emergency_contact_email", length = 255)
    private String emergencyContactEmail;

    @Column(name = "emergency_contact_phone", length = 40)
    private String emergencyContactPhone;

    @Column(name = "emergency_contact_relationship", length = 80)
    private String emergencyContactRelationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_source", nullable = false, length = 40)
    private BookingSource bookingSource = BookingSource.LOGGED_IN_USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_profile_id", nullable = false)
    private LocalProfile localProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experience_id", nullable = false)
    private Experience experience;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "availability_slot_id", nullable = false)
    private AvailabilitySlot availabilitySlot;

    @Column(name = "guests_count", nullable = false)
    private Integer guestsCount = 1;

    @Column(name = "adults_count", nullable = false)
    private Integer adultsCount = 0;

    @Column(name = "teens_count", nullable = false)
    private Integer teensCount = 0;

    @Column(name = "children_count", nullable = false)
    private Integer childrenCount = 0;

    @Column(name = "infants_count", nullable = false)
    private Integer infantsCount = 0;

    @Column(name = "is_private", nullable = false)
    private boolean privateBooking = false;

    @Column(name = "seats_blocked", nullable = false)
    private Integer seatsBlocked;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private BookingStatus status = BookingStatus.REQUESTED;

    /** No-show flag, set when an admin verifies a no-show report; resettable by admin. */
    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_outcome", nullable = false, length = 30)
    private AttendanceOutcome attendanceOutcome = AttendanceOutcome.NONE;

    @Column(name = "no_show_marked_at")
    private Instant noShowMarkedAt;

    /** The host's in-person show/no-show mark for this booking (operational, not an admin verdict). */
    @Enumerated(EnumType.STRING)
    @Column(name = "guest_show_status", nullable = false, length = 20)
    private GuestShowStatus guestShowStatus = GuestShowStatus.PENDING;

    @Column(name = "guest_show_marked_at")
    private Instant guestShowMarkedAt;

    @Column(name = "price_per_guest", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerGuest;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "traveler_note", columnDefinition = "TEXT")
    private String travelerNote;

    @Column(name = "local_response_note", columnDefinition = "TEXT")
    private String localResponseNote;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "guest_terms_accepted", nullable = false)
    private boolean guestTermsAccepted = false;

    @Column(name = "guest_safety_accepted", nullable = false)
    private boolean guestSafetyAccepted = false;

    @Column(name = "guest_liability_accepted", nullable = false)
    private boolean guestLiabilityAccepted = false;

    @Column(name = "guest_consent_version", length = 80)
    private String guestConsentVersion;

    @Column(name = "guest_consent_accepted_at")
    private Instant guestConsentAcceptedAt;

    @Column(name = "guest_consent_ip_address", length = 120)
    private String guestConsentIpAddress;

    @Column(name = "guest_consent_user_agent", columnDefinition = "TEXT")
    private String guestConsentUserAgent;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promo_code_id")
    private PromoCode promoCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_code_id")
    private ReferralCode referralCode;

    @Column(name = "original_amount", precision = 10, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "private_discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal privateDiscountAmount = BigDecimal.ZERO;

    @Column(name = "promo_code_text", length = 80)
    private String promoCodeText;

    @Column(name = "referral_code_text", length = 80)
    private String referralCodeText;

    @Column(name = "deal_id")
    private UUID dealId;

    @Column(name = "deal_discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal dealDiscountAmount = BigDecimal.ZERO;

    /** Discount the referred user received from a referral code — platform-borne (host earns on the pre-discount price). */
    @Column(name = "referral_discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal referralDiscountAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingPromoCode> appliedPromoCodes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        if (requestedAt == null) {
            requestedAt = now;
        }

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (guestsCount == null) {
            guestsCount = 1;
        }

        if (adultsCount == null) {
            adultsCount = 0;
        }

        if (teensCount == null) {
            teensCount = 0;
        }

        if (childrenCount == null) {
            childrenCount = 0;
        }

        if (infantsCount == null) {
            infantsCount = 0;
        }

        if (seatsBlocked == null) {
            seatsBlocked = guestsCount;
        }

        if (privateDiscountAmount == null) {
            privateDiscountAmount = BigDecimal.ZERO;
        }

        if (status == null) {
            status = BookingStatus.REQUESTED;
        }

        if (currency == null || currency.trim().isEmpty()) {
            currency = "EUR";
        }

        if (bookingSource == null) {
            bookingSource = loggedInUser == null ? BookingSource.GUEST_USER : BookingSource.LOGGED_IN_USER;
        }

        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }

        if (dealDiscountAmount == null) {
            dealDiscountAmount = BigDecimal.ZERO;
        }

        if (referralDiscountAmount == null) {
            referralDiscountAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Combined guest name ({@code guestFirstName + " " + guestLastName}), or {@code null} for a
     * logged-in booking with no guest details. Not a mapped column; with field-based JPA access
     * this derived getter is ignored by Hibernate. Kept so notification/calendar/attendance code
     * can render a single guest name.
     */
    public String getGuestName() {
        if (guestFirstName == null && guestLastName == null) {
            return null;
        }
        String first = guestFirstName == null ? "" : guestFirstName;
        String last = guestLastName == null ? "" : guestLastName;
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? null : combined;
    }
}