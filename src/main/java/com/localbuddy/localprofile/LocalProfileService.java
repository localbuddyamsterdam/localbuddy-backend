package com.localbuddy.localprofile;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public LocalProfileService(LocalProfileRepository localProfileRepository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               CityRepository cityRepository,
                               ExperienceCategoryRepository categoryRepository,
                               MediaStorageProvider storageProvider) {
        this.localProfileRepository = localProfileRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.cityRepository = cityRepository;
        this.categoryRepository = categoryRepository;
        this.storageProvider = storageProvider;
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
        return toResponse(savedProfile);
    }

    @Transactional
    public LocalProfileResponse submitMyLocalProfile(UUID userId) {
        LocalProfile profile = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));

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
        return toResponse(savedProfile);
    }


    private void validateLocalUser(User user) {
        if (user.getRole() != UserRole.LOCAL) {
            throw new BadRequestException("Only LOCAL users can create a local profile");
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
        profile.setDisplayName(requiredTrim(request.displayName()));
        profile.setPhoneNumber(requiredTrim(request.phoneNumber()));
        profile.setBio(requiredTrim(request.bio()));
        profile.setProfilePhotoUrl(requiredTrim(request.profilePhotoUrl()));
        profile.setHostCity(requiredTrim(request.hostCity()));
        profile.setZipCode(requiredTrim(request.zipCode()));
        profile.setCountry(requiredTrim(request.country()));

        profile.setExperienceLanguages(cleanRequiredList(request.experienceLanguages(), "experience language"));
        profile.setExperienceCities(resolveActiveCities(request.experienceCityIds()));
        profile.setExperienceCategories(resolveActiveCategories(request.experienceCategoryIds()));

        profile.setMotivation(requiredTrim(request.motivation()));
        profile.setExperienceInfo(requiredTrim(request.experienceInfo()));

        profile.setLegalFirstName(requiredTrim(request.legalFirstName()));
        profile.setLegalLastName(requiredTrim(request.legalLastName()));
        profile.setPreferredName(requiredTrim(request.preferredName()));
        profile.setCurrentAddress(requiredTrim(request.currentAddress()));

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
        profile.setDisplayName(requiredTrim(request.displayName()));
        profile.setPhoneNumber(requiredTrim(request.phoneNumber()));
        profile.setBio(requiredTrim(request.bio()));
        profile.setProfilePhotoUrl(requiredTrim(request.profilePhotoUrl()));
        profile.setHostCity(requiredTrim(request.hostCity()));
        profile.setZipCode(requiredTrim(request.zipCode()));
        profile.setCountry(requiredTrim(request.country()));

        profile.setExperienceLanguages(cleanRequiredList(request.experienceLanguages(), "experience language"));
        profile.setExperienceCities(resolveActiveCities(request.experienceCityIds()));
        profile.setExperienceCategories(resolveActiveCategories(request.experienceCategoryIds()));

        profile.setMotivation(requiredTrim(request.motivation()));
        profile.setExperienceInfo(requiredTrim(request.experienceInfo()));

        profile.setLegalFirstName(requiredTrim(request.legalFirstName()));
        profile.setLegalLastName(requiredTrim(request.legalLastName()));
        profile.setPreferredName(requiredTrim(request.preferredName()));
        profile.setCurrentAddress(requiredTrim(request.currentAddress()));

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
                profile.getUpdatedAt()
        );
    }

    private CityResponse toCityResponse(City city) {
        return new CityResponse(
                city.getId(),
                city.getName(),
                city.getSlug(),
                city.getCountry(),
                city.isActive(),
                city.getDisplayOrder()
        );
    }

    private ExperienceCategoryResponse toCategoryResponse(ExperienceCategory category) {
        return new ExperienceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
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

        LocalProfile savedProfile = localProfileRepository.save(profile);
        createLocalProfileApprovedNotification(savedProfile);
        return toResponse(savedProfile);
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
    public List<LocalProfileResponse> getApprovedLocalProfiles(String city) {
        if (city == null || city.trim().isEmpty()) {
            return localProfileRepository.findByApprovalStatus(LocalApprovalStatus.APPROVED)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return localProfileRepository
                .findByHostCityIgnoreCaseAndApprovalStatus(city.trim(), LocalApprovalStatus.APPROVED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocalProfileResponse getApprovedLocalProfileById(UUID profileId) {
        LocalProfile profile = localProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (profile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new ResourceNotFoundException("Local profile not found");
        }

        return toResponse(profile);
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
        notificationService.createEmailNotificationForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_CHANGES_REQUESTED,
                "Changes requested for your LocalBuddy profile",
                "Admin requested changes for your LocalBuddy profile: "
                        + nullSafe(profile.getChangesRequestedReason()),
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_CHANGES_REQUESTED:" + profile.getId() + ":" + profile.getReviewedAt()
        );
    }

    private void createLocalProfileRejectedNotification(LocalProfile profile) {
        notificationService.createEmailNotificationForUser(
                profile.getUser(),
                NotificationType.LOCAL_PROFILE_REJECTED,
                "Your LocalBuddy profile was rejected",
                "Your LocalBuddy profile was rejected: " + nullSafe(profile.getRejectionReason()),
                "LOCAL_PROFILE",
                profile.getId(),
                "LOCAL_PROFILE_REJECTED:" + profile.getId() + ":" + profile.getReviewedAt()
        );
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