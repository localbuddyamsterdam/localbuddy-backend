package com.localbuddy.localprofile;

import com.localbuddy.common.NameFormatter;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.City;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.experience.CityResponse;
import com.localbuddy.experience.ExperienceCategory;
import com.localbuddy.experience.ExperienceCategoryRepository;
import com.localbuddy.experience.ExperienceCategoryResponse;
import com.localbuddy.media.ImageUploadValidator;
import com.localbuddy.media.MediaStorageProvider;
import com.localbuddy.media.StoredObject;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LocalProfileService {

    private final LocalProfileRepository localProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final CityRepository cityRepository;
    private final ExperienceCategoryRepository categoryRepository;
    private final MediaStorageProvider storageProvider;
    /** Hard cap admins can assign (app.platform.max-commission-rate); guards host payouts. */
    private final BigDecimal maxCommissionRate;
    /** Frontend origin for deep links in host-application emails (edit form, admin queue). */
    private final String frontendBaseUrl;

    public LocalProfileService(LocalProfileRepository localProfileRepository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               CityRepository cityRepository,
                               ExperienceCategoryRepository categoryRepository,
                               MediaStorageProvider storageProvider,
                               @Value("${app.platform.max-commission-rate:0.50}") BigDecimal maxCommissionRate,
                               @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.localProfileRepository = localProfileRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.cityRepository = cityRepository;
        this.categoryRepository = categoryRepository;
        this.storageProvider = storageProvider;
        this.maxCommissionRate = maxCommissionRate;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Transactional
    public LocalProfileResponse createMyLocalProfile(UUID userId, CreateLocalProfileRequest request) {
        User user = getUser(userId);

        validateLocalUser(user);
        validateProfileDoesNotExist(userId);

        LocalProfile profile = new LocalProfile();
        profile.setUser(user);
        applyCreateRequest(profile, request);
        profile.setVerificationStatus(LocalVerificationStatus.NOT_STARTED);
        profile.setApprovalStatus(LocalApprovalStatus.DRAFT);

        LocalProfile savedProfile = localProfileRepository.save(profile);
        return toResponse(savedProfile);
    }

    @Transactional(readOnly = true)
    public LocalProfileResponse getMyLocalProfile(UUID userId) {
        LocalProfile profile = getProfileByUserId(userId);
        return toResponse(profile);
    }

    @Transactional
    public LocalProfileResponse uploadMyProfilePhoto(UUID userId, byte[] data, String contentType, String filename) {
        LocalProfile profile = getProfileByUserId(userId);
        ImageUploadValidator.validate(data, contentType);

        String previousKey = profile.getProfilePhotoStorageKey();
        StoredObject stored = storageProvider.upload("profiles", data, contentType, filename);
        profile.setProfilePhotoUrl(stored.url());
        profile.setProfilePhotoStorageKey(stored.storageKey());
        LocalProfile saved = localProfileRepository.save(profile);

        // Best-effort cleanup of the previously stored blob (if any).
        if (previousKey != null && !previousKey.isBlank()) {
            storageProvider.delete(previousKey);
        }
        return toResponse(saved);
    }

    @Transactional
    public LocalProfileResponse deleteMyProfilePhoto(UUID userId) {
        LocalProfile profile = getProfileByUserId(userId);
        String key = profile.getProfilePhotoStorageKey();
        profile.setProfilePhotoUrl(null);
        profile.setProfilePhotoStorageKey(null);
        LocalProfile saved = localProfileRepository.save(profile);
        if (key != null && !key.isBlank()) {
            storageProvider.delete(key);
        }
        return toResponse(saved);
    }

    @Transactional
    public LocalProfileResponse updateMyLocalProfile(UUID userId, UpdateLocalProfileRequest request) {
        LocalProfile profile = getProfileByUserId(userId);

        if (profile.getApprovalStatus() == LocalApprovalStatus.BLOCKED) {
            throw new BadRequestException("Blocked local profile cannot be updated");
        }

        if (profile.getApprovalStatus() != LocalApprovalStatus.DRAFT &&
                profile.getApprovalStatus() != LocalApprovalStatus.CHANGES_REQUESTED &&
                profile.getApprovalStatus() != LocalApprovalStatus.REJECTED &&
                profile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Only draft, changes requested, rejected, or approved profiles can be updated");
        }

        LocalApprovalStatus previousStatus = profile.getApprovalStatus();

        applyUpdateRequest(profile, request);

        if (previousStatus == LocalApprovalStatus.CHANGES_REQUESTED ||
                previousStatus == LocalApprovalStatus.REJECTED) {
            profile.setApprovalStatus(LocalApprovalStatus.DRAFT);
            profile.setReviewedAt(null);
            profile.setAdminReviewNote(null);
            profile.setRejectionReason(null);
            profile.setChangesRequestedReason(null);
        }

        if (previousStatus == LocalApprovalStatus.APPROVED) {
            profile.setApprovalStatus(LocalApprovalStatus.SUBMITTED);
            profile.setReviewedAt(null);
            profile.setAdminReviewNote(null);
            profile.setRejectionReason(null);
            profile.setChangesRequestedReason(null);
            profile.setResubmittedAt(Instant.now());
        }

        LocalProfile savedProfile = localProfileRepository.save(profile);
        // An approved host editing their profile pushes it back to SUBMITTED for
        // re-review, so treat it as a resubmission and alert the admins.
        if (previousStatus == LocalApprovalStatus.APPROVED) {
            notifyAdminsOfLocalProfileSubmission(savedProfile);
        }
        return toResponse(savedProfile);
    }

    @Transactional
    public LocalProfileResponse submitMyLocalProfile(UUID userId) {
        LocalProfile profile = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

        // Submitting something already awaiting review is a no-op, not a failure.
        // Rejecting it turns an ordinary double-click — or any client that saves
        // then submits — into a 400 for a profile that is exactly where it should
        // be, with nothing the user can do to clear it. This also covers an
        // approved host editing their profile: the update flips them back to
        // SUBMITTED, so the submit that follows lands here rather than on the
        // error below.
        if (profile.getApprovalStatus() == LocalApprovalStatus.SUBMITTED) {
            return toResponse(profile);
        }

        if (profile.getApprovalStatus() != LocalApprovalStatus.DRAFT &&
                profile.getApprovalStatus() != LocalApprovalStatus.CHANGES_REQUESTED &&
                profile.getApprovalStatus() != LocalApprovalStatus.REJECTED) {
            throw new BadRequestException("Only draft, changes requested, or rejected profiles can be submitted");
        }

        boolean isResubmission = profile.getSubmittedAt() != null ||
                profile.getApprovalStatus() == LocalApprovalStatus.CHANGES_REQUESTED ||
                profile.getApprovalStatus() == LocalApprovalStatus.REJECTED;

        profile.setApprovalStatus(LocalApprovalStatus.SUBMITTED);

        if (profile.getSubmittedAt() == null) {
            profile.setSubmittedAt(Instant.now());
        }

        if (isResubmission) {
            profile.setResubmittedAt(Instant.now());
        }

        profile.setAdminReviewNote(null);
        profile.setRejectionReason(null);
        profile.setChangesRequestedReason(null);
        profile.setReviewedAt(null);

        LocalProfile savedProfile = localProfileRepository.save(profile);
        createLocalProfileSubmittedNotification(savedProfile);
        notifyAdminsOfLocalProfileSubmission(savedProfile);
        return toResponse(savedProfile);
    }


    private void validateLocalUser(User user) {
        // Travellers may apply to become hosts (one account covers both journeys);
        // approval upgrades their role to LOCAL. Only admins are excluded.
        if (user.getRole() != UserRole.LOCAL && user.getRole() != UserRole.LOGGED_IN_USER) {
            throw new BadRequestException("This account type cannot create a host profile");
        }
    }

    private void validateProfileDoesNotExist(UUID userId) {
        if (localProfileRepository.existsByUserId(userId)) {
            throw new BadRequestException("Local profile already exists");
        }
    }

    private User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));
    }

    private LocalProfile getProfileByUserId(UUID userId) {
        return localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));
    }

    private void applyCreateRequest(LocalProfile profile, CreateLocalProfileRequest request) {
        profile.setDisplayName(NameFormatter.titleCase(requiredTrim(request.displayName())));
        profile.setPhoneNumber(requiredTrim(request.phoneNumber()));
        profile.setWhatsappOptIn(Boolean.TRUE.equals(request.whatsappOptIn()));
        profile.setBio(requiredTrim(request.bio()));
        profile.setProfilePhotoUrl(optionalTrim(request.profilePhotoUrl()));
        profile.setHostCity(requiredTrim(request.hostCity()));
        profile.setZipCode(requiredTrim(request.zipCode()));
        profile.setCountry(requiredTrim(request.country()));

        profile.setExperienceLanguages(cleanRequiredList(request.experienceLanguages(), "experience language"));
        profile.setExperienceCities(resolveActiveCities(request.experienceCityIds()));
        profile.setExperienceCategories(resolveActiveCategories(request.experienceCategoryIds()));

        profile.setMotivation(requiredTrim(request.motivation()));
        profile.setExperienceInfo(requiredTrim(request.experienceInfo()));

        profile.setLegalFirstName(NameFormatter.requiredName(request.legalFirstName(), "Legal first name", NameFormatter.FIRST_NAME_MIN));
        profile.setLegalLastName(NameFormatter.requiredName(request.legalLastName(), "Legal last name", NameFormatter.LAST_NAME_MIN));
        profile.setPreferredName(NameFormatter.titleCase(requiredTrim(request.preferredName())));
        profile.setCurrentAddress(requiredTrim(request.currentAddress()));
        profile.setGender(request.gender());

        profile.setAccountNumber(optionalTrim(request.accountNumber()));
        profile.setAccountName(optionalTrim(request.accountName()));
        profile.setSwiftCode(optionalTrim(request.swiftCode()));

        if (request.vatRegistered() != null) {
            profile.setVatRegistered(request.vatRegistered());
        }
        profile.setVatNumber(optionalTrim(request.vatNumber()));
        profile.setTaxCountry(optionalUpper(request.taxCountry()));
        profile.setLegalEntityType(optionalTrim(request.legalEntityType()));
        profile.setTaxIdentificationNumber(optionalTrim(request.taxIdentificationNumber()));
        profile.setBusinessRegistrationNumber(optionalTrim(request.businessRegistrationNumber()));
        profile.setDateOfBirth(request.dateOfBirth());
    }

    private void applyUpdateRequest(LocalProfile profile, UpdateLocalProfileRequest request) {
        profile.setDisplayName(NameFormatter.titleCase(requiredTrim(request.displayName())));
        profile.setPhoneNumber(requiredTrim(request.phoneNumber()));
        profile.setWhatsappOptIn(Boolean.TRUE.equals(request.whatsappOptIn()));
        profile.setBio(requiredTrim(request.bio()));
        profile.setProfilePhotoUrl(optionalTrim(request.profilePhotoUrl()));
        profile.setHostCity(requiredTrim(request.hostCity()));
        profile.setZipCode(requiredTrim(request.zipCode()));
        profile.setCountry(requiredTrim(request.country()));

        profile.setExperienceLanguages(cleanRequiredList(request.experienceLanguages(), "experience language"));
        profile.setExperienceCities(resolveActiveCities(request.experienceCityIds()));
        profile.setExperienceCategories(resolveActiveCategories(request.experienceCategoryIds()));

        profile.setMotivation(requiredTrim(request.motivation()));
        profile.setExperienceInfo(requiredTrim(request.experienceInfo()));

        profile.setLegalFirstName(NameFormatter.requiredName(request.legalFirstName(), "Legal first name", NameFormatter.FIRST_NAME_MIN));
        profile.setLegalLastName(NameFormatter.requiredName(request.legalLastName(), "Legal last name", NameFormatter.LAST_NAME_MIN));
        profile.setPreferredName(NameFormatter.titleCase(requiredTrim(request.preferredName())));
        profile.setCurrentAddress(requiredTrim(request.currentAddress()));
        profile.setGender(request.gender());

        profile.setAccountNumber(optionalTrim(request.accountNumber()));
        profile.setAccountName(optionalTrim(request.accountName()));
        profile.setSwiftCode(optionalTrim(request.swiftCode()));
    }

    private List<City> resolveActiveCities(List<UUID> cityIds) {
        List<City> cities = new ArrayList<>();

        for (UUID cityId : new LinkedHashSet<>(cityIds)) {
            City city = cityRepository.findById(cityId)
                    .orElseThrow(() -> new BadRequestException("Invalid city selected"));

            if (!city.isActive()) {
                throw new BadRequestException("City '" + city.getName() + "' is not available");
            }

            cities.add(city);
        }

        return cities;
    }

    private List<ExperienceCategory> resolveActiveCategories(List<UUID> categoryIds) {
        List<ExperienceCategory> categories = new ArrayList<>();

        for (UUID categoryId : new LinkedHashSet<>(categoryIds)) {
            ExperienceCategory category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new BadRequestException("Invalid experience category selected"));

            if (!category.isActive()) {
                throw new BadRequestException("Experience category '" + category.getName() + "' is not available");
            }

            categories.add(category);
        }

        return categories;
    }

    private List<String> cleanRequiredList(List<String> values, String label) {
        List<String> cleaned = cleanList(values);

        if (cleaned.isEmpty()) {
            throw new BadRequestException("At least one " + label + " is required");
        }

        return cleaned;
    }

    private String requiredTrim(String value) {
        return value.trim();
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String optionalUpper(String value) {
        String trimmed = optionalTrim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private LocalProfileResponse toResponse(LocalProfile profile) {
        return new LocalProfileResponse(
                profile.getId(),
                profile.getUser().getId(),

                profile.getDisplayName(),
                profile.getPhoneNumber(),
                profile.isWhatsappOptIn(),
                profile.getBio(),
                profile.getProfilePhotoUrl(),
                profile.getHostCity(),
                profile.getZipCode(),
                profile.getCountry(),

                profile.getExperienceLanguages(),
                profile.getExperienceCities().stream().map(this::toCityResponse).toList(),
                profile.getExperienceCategories().stream().map(this::toCategoryResponse).toList(),

                profile.getMotivation(),
                profile.getExperienceInfo(),

                profile.getVerificationStatus(),
                profile.getApprovalStatus(),

                profile.getAdminReviewNote(),
                profile.getRejectionReason(),
                profile.getChangesRequestedReason(),
                profile.getReviewedAt(),
                profile.getSubmittedAt(),
                profile.getResubmittedAt(),

                profile.getLegalFirstName(),
                profile.getLegalLastName(),
                profile.getPreferredName(),
                profile.getCurrentAddress(),

                profile.getAccountNumber(),
                profile.getAccountName(),
                profile.getSwiftCode(),

                profile.isVatRegistered(),
                profile.getVatNumber(),
                profile.getTaxCountry(),
                profile.getLegalEntityType(),
                profile.getTaxIdentificationNumber(),
                profile.getBusinessRegistrationNumber(),
                profile.getDateOfBirth(),

                profile.getVerificationProvider(),
                profile.getVerificationReferenceId(),
                profile.getVerificationStartedAt(),
                profile.getVerificationCompletedAt(),
                profile.getVerificationFailureReason(),

                profile.getRatingAvg(),
                profile.getTotalReviews(),

                profile.getCreatedAt(),
                profile.getUpdatedAt(),

                profile.getCommissionRate(),
                profile.getGender()
        );
    }

    /**
     * PII-free projection served to anonymous callers via the public endpoints.
     * Keeps only public browse/search fields; never expose contact, address,
     * bank, tax, verification-internal, or commission data here.
     */
    private PublicLocalProfileResponse toPublicResponse(LocalProfile profile) {
        return new PublicLocalProfileResponse(
                profile.getId(),
                profile.getUser().getId(),

                profile.getDisplayName(),
                profile.getPreferredName(),
                profile.getLegalFirstName(),
                profile.getLegalLastName(),

                profile.getProfilePhotoUrl(),
                profile.getHostCity(),
                profile.getCountry(),
                profile.getBio(),

                profile.getExperienceLanguages(),
                profile.getExperienceCategories().stream().map(this::toCategoryResponse).toList(),

                profile.getGender(),
                profile.getVerificationStatus(),

                profile.getRatingAvg(),
                profile.getTotalReviews(),

                profile.getCreatedAt()
        );
    }

    private CityResponse toCityResponse(City city) {
        return new CityResponse(
                city.getId(),
                city.getName(),
                city.getSlug(),
                city.getCountry(),
                city.getLatitude(),
                city.getLongitude(),
                city.isActive(),
                city.getDisplayOrder(),
                city.getTimezone()
        );
    }

    private ExperienceCategoryResponse toCategoryResponse(ExperienceCategory category) {
        return new ExperienceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getImageUrl(),
                category.isActive(),
                category.getDisplayOrder()
        );
    }

    @Transactional(readOnly = true)
    public List<LocalProfileResponse> getPendingLocalProfiles() {
        return localProfileRepository.findByApprovalStatus(LocalApprovalStatus.SUBMITTED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public LocalProfileResponse approveLocalProfile(UUID profileId) {
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (profile.getApprovalStatus() == LocalApprovalStatus.BLOCKED) {
            throw new BadRequestException("Blocked local profile cannot be approved");
        }

        if (profile.getApprovalStatus() != LocalApprovalStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted local profiles can be approved");
        }

        profile.setApprovalStatus(LocalApprovalStatus.APPROVED);
        profile.setReviewedAt(Instant.now());

        profile.setAdminReviewNote(null);
        profile.setRejectionReason(null);
        profile.setChangesRequestedReason(null);

        // Approval is what makes someone a host: travellers who applied are
        // upgraded to LOCAL here (the frontend re-reads the session user after
        // approval, so host navigation/permissions appear without re-login).
        User applicant = profile.getUser();
        if (applicant != null && applicant.getRole() == UserRole.LOGGED_IN_USER) {
            applicant.setRole(UserRole.LOCAL);
            userRepository.save(applicant);
        }

        LocalProfile savedProfile = localProfileRepository.save(profile);
        createLocalProfileApprovedNotification(savedProfile);
        return toResponse(savedProfile);
    }

    /**
     * Set or clear a host's per-host commission override (admin only).
     * {@code commissionRate == null} clears it. The override supersedes host/
     * category/city/platform commission rules, so it is capped at
     * {@code app.platform.max-commission-rate}.
     */
    @Transactional
    public LocalProfileResponse setCommissionOverride(UUID profileId, BigDecimal commissionRate) {
        validateCommissionOverrideRate(commissionRate);
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));
        profile.setCommissionRate(commissionRate);
        return toResponse(localProfileRepository.save(profile));
    }

    /**
     * Bulk variant of {@link #setCommissionOverride}: same rate (or clear) applied to every
     * listed host atomically — one unknown id fails the whole batch so a partial apply can't
     * go unnoticed.
     */
    @Transactional
    public List<LocalProfileResponse> setCommissionOverrideBulk(List<UUID> profileIds, BigDecimal commissionRate) {
        validateCommissionOverrideRate(commissionRate);
        List<UUID> ids = profileIds.stream().distinct().toList();
        List<LocalProfile> profiles = localProfileRepository.findAllById(ids);
        if (profiles.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more local profiles were not found");
        }
        profiles.forEach(p -> p.setCommissionRate(commissionRate));
        return localProfileRepository.saveAll(profiles).stream().map(this::toResponse).toList();
    }

    private void validateCommissionOverrideRate(BigDecimal commissionRate) {
        if (commissionRate != null) {
            if (commissionRate.signum() < 0) {
                throw new BadRequestException("Commission rate must be zero or positive");
            }
            if (commissionRate.compareTo(maxCommissionRate) > 0) {
                throw new BadRequestException("Commission rate " + commissionRate
                        + " exceeds the maximum allowed (" + maxCommissionRate + ")");
            }
        }
    }


    @Transactional
    public LocalProfileResponse rejectLocalProfile(UUID profileId, AdminLocalProfileReviewRequest request) {
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (profile.getApprovalStatus() == LocalApprovalStatus.BLOCKED) {
            throw new BadRequestException("Blocked local profile cannot be rejected");
        }

        if (profile.getApprovalStatus() != LocalApprovalStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted local profiles can be rejected");
        }

        profile.setApprovalStatus(LocalApprovalStatus.REJECTED);
        profile.setReviewedAt(Instant.now());
        profile.setRejectionReason(requiredTrim(request.reason()));
        profile.setAdminReviewNote(optionalTrim(request.adminNote()));
        profile.setChangesRequestedReason(null);

        LocalProfile savedProfile = localProfileRepository.save(profile);
        createLocalProfileRejectedNotification(savedProfile);
        return toResponse(savedProfile);
    }

    @Transactional(readOnly = true)
    public List<PublicLocalProfileResponse> getApprovedLocalProfiles(String city) {
        if (city == null || city.trim().isEmpty()) {
            return localProfileRepository.findByApprovalStatus(LocalApprovalStatus.APPROVED)
                    .stream()
                    .map(this::toPublicResponse)
                    .toList();
        }

        return localProfileRepository
                .findByHostCityIgnoreCaseAndApprovalStatus(city.trim(), LocalApprovalStatus.APPROVED)
                .stream()
                .map(this::toPublicResponse)
                .toList();
    }

    /**
     * Paginated public browse of approved locals (optionally by city). Newest first;
     * page is zero-based and size is clamped to a sane range.
     */
    @Transactional(readOnly = true)
    public LocalProfilePageResponse getApprovedLocalProfilesPaged(String city, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(60, Math.max(1, size));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                safePage, safeSize,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));

        org.springframework.data.domain.Page<LocalProfile> result =
                (city == null || city.trim().isEmpty())
                        ? localProfileRepository.findByApprovalStatus(LocalApprovalStatus.APPROVED, pageable)
                        : localProfileRepository.findByHostCityIgnoreCaseAndApprovalStatus(
                                city.trim(), LocalApprovalStatus.APPROVED, pageable);

        return new LocalProfilePageResponse(
                result.getContent().stream().map(this::toPublicResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public PublicLocalProfileResponse getApprovedLocalProfileById(UUID profileId) {
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (profile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new ResourceNotFoundException("Local profile not found");
        }

        return toPublicResponse(profile);
    }

    @Transactional
    public LocalProfileResponse requestChangesForLocalProfile(
            UUID profileId,
            AdminLocalProfileReviewRequest request
    ) {
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (profile.getApprovalStatus() == LocalApprovalStatus.BLOCKED) {
            throw new BadRequestException("Blocked local profile cannot request changes");
        }

        if (profile.getApprovalStatus() != LocalApprovalStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted local profiles can request changes");
        }

        profile.setApprovalStatus(LocalApprovalStatus.CHANGES_REQUESTED);
        profile.setReviewedAt(Instant.now());
        profile.setAdminReviewNote(optionalTrim(request.adminNote()));
        profile.setChangesRequestedReason(requiredTrim(request.reason()));
        profile.setRejectionReason(null);

        LocalProfile savedProfile = localProfileRepository.save(profile);
        createLocalProfileChangesRequestedNotification(savedProfile);
        return toResponse(savedProfile);
    }

    private void createLocalProfileSubmittedNotification(LocalProfile profile) {
        notificationService.createEmailNotificationForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_SUBMITTED,
                "Your LocalBuddy profile was submitted",
                "Your LocalBuddy profile has been submitted for admin review.",
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_SUBMITTED:" + profile.getId()
        );
    }

    private void createLocalProfileApprovedNotification(LocalProfile profile) {
        notificationService.createEmailNotificationForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_APPROVED,
                "Your LocalBuddy profile was approved",
                "Your LocalBuddy profile has been approved. You can now create experiences.",
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_APPROVED:" + profile.getId()
        );
    }

    private void createLocalProfileChangesRequestedNotification(LocalProfile profile) {
        String reason = nullSafe(profile.getChangesRequestedReason());
        notificationService.createActionEmailForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_CHANGES_REQUESTED,
                "Changes requested for your LocalBuddy profile",
                "Our team reviewed your host application and needs a few changes before it can be approved."
                        + (reason.isBlank() ? "" : "\n\nWhat to update:\n" + reason),
                "Update my application",
                frontendLink("/become-host"),
                "Open your application, make the changes above, and submit it again for review.",
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_CHANGES_REQUESTED:" + profile.getId() + ":" + profile.getReviewedAt()
        );
    }

    private void createLocalProfileRejectedNotification(LocalProfile profile) {
        String reason = nullSafe(profile.getRejectionReason());
        notificationService.createActionEmailForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_REJECTED,
                "Your LocalBuddy profile was not approved",
                "Unfortunately your host application wasn't approved this time."
                        + (reason.isBlank() ? "" : "\n\nReason:\n" + reason),
                "Reapply",
                frontendLink("/become-host"),
                "You can update your details and reapply whenever you're ready.",
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_REJECTED:" + profile.getId() + ":" + profile.getReviewedAt()
        );
    }

    /**
     * Notify every admin that a host application is waiting for review — sent on
     * submit and on every resubmit. Each admin gets their own email (dedupe keyed
     * per recipient + this submission's timestamp so a later resubmit re-notifies).
     */
    private void notifyAdminsOfLocalProfileSubmission(LocalProfile profile) {
        List<User> admins = new ArrayList<>(userRepository.findByRole(UserRole.ADMIN));
        admins.addAll(userRepository.findByRole(UserRole.SUPER_ADMIN));
        String applicantName = displayNameFor(profile);
        Instant when = profile.getResubmittedAt() != null ? profile.getResubmittedAt() : profile.getSubmittedAt();
        for (User admin : admins) {
            notificationService.createActionEmailForUser(
                    admin,
                    NotificationType.LOCAL_PROFILE_SUBMITTED,
                    "New host application to review",
                    applicantName + " submitted a host application for " + nullSafe(profile.getHostCity())
                            + " and it's waiting for your review.",
                    "Review application",
                    frontendLink("/admin"),
                    "You're receiving this because you're a LocalBuddy admin.",
                    "LOCAL_PROFILE",
                    profile.getId(),
                    "LOCAL_PROFILE_ADMIN_REVIEW:" + profile.getId() + ":" + admin.getId() + ":" + when
            );
        }
    }

    /** Applicant's best display name for admin-facing copy (legal name, else preferred/display). */
    private String displayNameFor(LocalProfile profile) {
        String legal = ((nullSafe(profile.getLegalFirstName()) + " " + nullSafe(profile.getLegalLastName())).trim());
        if (!legal.isBlank()) {
            return legal;
        }
        String preferred = nullSafe(profile.getPreferredName());
        return preferred.isBlank() ? "A new host" : preferred;
    }

    /** Join the configured frontend origin with a path, tolerating a trailing slash. */
    private String frontendLink(String path) {
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    @Transactional(readOnly = true)
    public LocalOnboardingStatusResponse getMyOnboardingStatus(UUID userId) {
        User user = getUser(userId);
        validateLocalUser(user);

        return localProfileRepository.findByUserId(userId)
                .map(profile -> {
                    LocalApprovalStatus approvalStatus = profile.getApprovalStatus();

                    boolean canEdit = approvalStatus == LocalApprovalStatus.DRAFT
                            || approvalStatus == LocalApprovalStatus.CHANGES_REQUESTED
                            || approvalStatus == LocalApprovalStatus.REJECTED
                            || approvalStatus == LocalApprovalStatus.APPROVED;

                    boolean canSubmit = approvalStatus == LocalApprovalStatus.DRAFT
                            || approvalStatus == LocalApprovalStatus.CHANGES_REQUESTED
                            || approvalStatus == LocalApprovalStatus.REJECTED;

                    boolean canCreateExperience = approvalStatus == LocalApprovalStatus.APPROVED;

                    return new LocalOnboardingStatusResponse(
                            true,
                            profile.getId(),
                            approvalStatus,
                            profile.getVerificationStatus(),
                            canEdit,
                            canSubmit,
                            canCreateExperience,
                            buildOnboardingMessage(profile)
                    );
                })
                .orElseGet(() -> new LocalOnboardingStatusResponse(
                        false,
                        null,
                        null,
                        LocalVerificationStatus.NOT_STARTED,
                        true,
                        false,
                        false,
                        "Please create your LocalBuddy profile to continue onboarding."
                ));
    }

    private String buildOnboardingMessage(LocalProfile profile) {
        return switch (profile.getApprovalStatus()) {
            case DRAFT -> "Your profile is in draft. Please complete and submit it for review.";
            case SUBMITTED -> "Your profile has been submitted and is waiting for admin review.";
            case CHANGES_REQUESTED -> "Admin requested changes. Please update and resubmit your profile.";
            case APPROVED -> "Your profile is approved. You can now create experiences.";
            case REJECTED -> "Your profile was rejected. Please review the reason and contact support if needed.";
            case BLOCKED -> "Your profile is blocked. Please contact support.";
        };
    }

    private String nullSafe(String value) {
        return value == null || value.trim().isEmpty() ? "No reason provided." : value.trim();
    }
}