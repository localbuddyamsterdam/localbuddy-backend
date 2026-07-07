package com.localbuddy.localprofile;

import com.localbuddy.experience.City;
import com.localbuddy.experience.ExperienceCategory;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "local_profiles")
@Getter
@Setter
@NoArgsConstructor
public class LocalProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(name = "bio", nullable = false, columnDefinition = "TEXT")
    private String bio;

    @Column(name = "phone_number", nullable = false, length = 40)
    private String phoneNumber;

    @Column(name = "host_city", nullable = false, length = 100)
    private String hostCity;

    @Column(name = "zip_code", nullable = false, length = 20)
    private String zipCode;

    @Column(name = "country", nullable = false, length = 100)
    private String country;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "experience_languages", columnDefinition = "jsonb")
    private List<String> experienceLanguages = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "local_profile_experience_cities",
            joinColumns = @JoinColumn(name = "local_profile_id"),
            inverseJoinColumns = @JoinColumn(name = "city_id")
    )
    private List<City> experienceCities = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "local_profile_experience_categories",
            joinColumns = @JoinColumn(name = "local_profile_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private List<ExperienceCategory> experienceCategories = new ArrayList<>();

    @Column(name = "motivation", nullable = false, columnDefinition = "TEXT")
    private String motivation;

    @Column(name = "experience_info", nullable = false, columnDefinition = "TEXT")
    private String experienceInfo;

    @Column(name = "profile_photo_url", nullable = false, columnDefinition = "TEXT")
    private String profilePhotoUrl;

    @Column(name = "profile_photo_storage_key", length = 512)
    private String profilePhotoStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 40)
    private LocalVerificationStatus verificationStatus = LocalVerificationStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 40)
    private LocalApprovalStatus approvalStatus = LocalApprovalStatus.DRAFT;

    @Column(name = "admin_review_note", columnDefinition = "TEXT")
    private String adminReviewNote;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "changes_requested_reason", columnDefinition = "TEXT")
    private String changesRequestedReason;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "resubmitted_at")
    private Instant resubmittedAt;

    @Column(name = "legal_first_name", nullable = false, length = 120)
    private String legalFirstName;

    @Column(name = "legal_last_name", nullable = false, length = 120)
    private String legalLastName;

    @Column(name = "preferred_name", nullable = false, length = 120)
    private String preferredName;

    @Column(name = "current_address", nullable = false, columnDefinition = "TEXT")
    private String currentAddress;

    @Column(name = "account_number", length = 64)
    private String accountNumber;

    @Column(name = "account_name", length = 150)
    private String accountName;

    @Column(name = "swift_code", length = 32)
    private String swiftCode;

    // ---- Tax / VAT / DAC7 + per-host commission override ----

    @Column(name = "vat_registered", nullable = false)
    private boolean vatRegistered = false;

    @Column(name = "vat_number", length = 40)
    private String vatNumber;

    /** ISO-2 country of tax residence (e.g. NL); drives VAT treatment. */
    @Column(name = "tax_country", length = 2)
    private String taxCountry;

    @Column(name = "legal_entity_type", length = 20)
    private String legalEntityType;

    /** DAC7 tax identification number. */
    @Column(name = "tax_identification_number", length = 60)
    private String taxIdentificationNumber;

    @Column(name = "business_registration_number", length = 60)
    private String businessRegistrationNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Optional per-host commission override; used when an experience has none. */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "stripe_connect_account_id", length = 255)
    private String stripeConnectAccountId;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled = false;

    @Column(name = "verification_provider", length = 80)
    private String verificationProvider;

    @Column(name = "verification_reference_id")
    private String verificationReferenceId;

    @Column(name = "verification_started_at")
    private Instant verificationStartedAt;

    @Column(name = "verification_completed_at")
    private Instant verificationCompletedAt;

    @Column(name = "verification_failure_reason", columnDefinition = "TEXT")
    private String verificationFailureReason;

    @Column(name = "rating_avg", nullable = false, precision = 3, scale = 2)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "total_reviews", nullable = false)
    private Integer totalReviews = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (verificationStatus == null) {
            verificationStatus = LocalVerificationStatus.NOT_STARTED;
        }

        if (approvalStatus == null) {
            approvalStatus = LocalApprovalStatus.DRAFT;
        }

        if (ratingAvg == null) {
            ratingAvg = BigDecimal.ZERO;
        }

        if (totalReviews == null) {
            totalReviews = 0;
        }

        if (experienceLanguages == null) {
            experienceLanguages = new ArrayList<>();
        }

        if (experienceCities == null) {
            experienceCities = new ArrayList<>();
        }

        if (experienceCategories == null) {
            experienceCategories = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}