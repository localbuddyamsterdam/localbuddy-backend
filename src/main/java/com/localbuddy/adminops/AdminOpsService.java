package com.localbuddy.adminops;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.*;
import com.localbuddy.localprofile.*;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminOpsService {

    private final UserRepository userRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ExperienceRepository experienceRepository;
    private final ExperienceCategoryRepository experienceCategoryRepository;
    private final CityRepository cityRepository;

    public AdminOpsService(UserRepository userRepository,
                           LocalProfileRepository localProfileRepository,
                           ExperienceRepository experienceRepository,
                           ExperienceCategoryRepository experienceCategoryRepository,
                           CityRepository cityRepository) {
        this.userRepository = userRepository;
        this.localProfileRepository = localProfileRepository;
        this.experienceRepository = experienceRepository;
        this.experienceCategoryRepository = experienceCategoryRepository;
        this.cityRepository = cityRepository;
    }

    @Transactional
    public LocalProfileResponse createOrApproveLocalProfile(AdminCreateLocalProfileRequest request) {
        String email = requiredTrim(request.email()).toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for email: " + email));

        if (user.getRole() != UserRole.LOCAL) {
            throw new BadRequestException("User must have LOCAL role");
        }

        LocalProfile profile = localProfileRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    LocalProfile newProfile = new LocalProfile();
                    newProfile.setUser(user);
                    return newProfile;
                });

        profile.setDisplayName(requiredTrim(request.displayName()));
        profile.setPhoneNumber(defaulted(request.phoneNumber()));
        profile.setBio(defaulted(request.bio()));
        profile.setProfilePhotoUrl(defaulted(request.profilePhotoUrl()));
        profile.setHostCity(defaulted(request.hostCity()));
        profile.setZipCode(defaulted(request.zipCode()));
        profile.setCountry(defaulted(request.country()));
        profile.setExperienceLanguages(cleanList(request.experienceLanguages()));
        profile.setExperienceCities(resolveCities(request.experienceCityIds()));
        profile.setExperienceCategories(resolveCategories(request.experienceCategoryIds()));
        profile.setMotivation(defaulted(request.motivation()));
        profile.setExperienceInfo(defaulted(request.experienceInfo()));
        profile.setLegalFirstName(defaulted(request.legalFirstName()));
        profile.setLegalLastName(defaulted(request.legalLastName()));
        profile.setPreferredName(defaulted(request.preferredName()));
        profile.setCurrentAddress(defaulted(request.currentAddress()));
        profile.setAccountNumber(optionalTrim(request.accountNumber()));
        profile.setAccountName(optionalTrim(request.accountName()));
        profile.setSwiftCode(optionalTrim(request.swiftCode()));

        profile.setVerificationStatus(LocalVerificationStatus.MANUALLY_APPROVED);
        profile.setApprovalStatus(LocalApprovalStatus.APPROVED);
        profile.setReviewedAt(Instant.now());
        profile.setSubmittedAt(profile.getSubmittedAt() == null ? Instant.now() : profile.getSubmittedAt());
        profile.setAdminReviewNote(optionalTrim(request.adminNote()));
        profile.setRejectionReason(null);
        profile.setChangesRequestedReason(null);

        LocalProfile savedProfile = localProfileRepository.save(profile);
        return toLocalProfileResponse(savedProfile);
    }

    @Transactional
    public ExperienceResponse createApprovedExperience(AdminCreateExperienceRequest request) {
        LocalProfile localProfile = localProfileRepository.findById(request.localProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Local profile not found"));

        if (localProfile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Local profile must be approved before creating experience");
        }

        City city = cityRepository.findById(request.cityId())
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));

        Experience experience = new Experience();
        experience.setLocalProfile(localProfile);
        experience.setCity(city);

        if (request.categoryId() != null) {
            ExperienceCategory category = experienceCategoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Experience category not found"));
            experience.setCategory(category);
        } else {
            experience.setCategory(null);
        }

        experience.setTitle(requiredTrim(request.title()));
        experience.setSlug(generateSlug(request.title()));
        experience.setDescription(requiredTrim(request.description()));
        experience.setMeetingArea(optionalTrim(request.meetingArea()));
        experience.setDurationMinutes(request.durationMinutes());
        experience.setPriceAmount(normalizeAmount(request.priceAmount()));
        experience.setCurrency(requiredTrim(request.currency()).toUpperCase(Locale.ROOT));
        experience.setMaxGuests(request.maxGuests());
        experience.setSafetyNotes(optionalTrim(request.safetyNotes()));
        experience.setStatus(ExperienceStatus.APPROVED);

        Experience savedExperience = experienceRepository.save(experience);
        return toExperienceResponse(savedExperience);
    }

    private LocalProfileResponse toLocalProfileResponse(LocalProfile profile) {
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
                category.getDisplayOrder()
        );
    }

    private List<City> resolveCities(List<UUID> cityIds) {
        if (cityIds == null) {
            return new ArrayList<>();
        }

        List<City> cities = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(cityIds)) {
            cities.add(cityRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("City not found")));
        }
        return cities;
    }

    private List<ExperienceCategory> resolveCategories(List<UUID> categoryIds) {
        if (categoryIds == null) {
            return new ArrayList<>();
        }

        List<ExperienceCategory> categories = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(categoryIds)) {
            categories.add(experienceCategoryRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Experience category not found")));
        }
        return categories;
    }

    private String defaulted(String value) {
        return value == null ? "" : value.trim();
    }

    private ExperienceResponse toExperienceResponse(Experience experience) {
        return new ExperienceResponse(
                experience.getId(),
                experience.getLocalProfile().getId(),
                experience.getCategory() != null ? experience.getCategory().getId() : null,
                experience.getCategory() != null ? experience.getCategory().getName() : null,
                experience.getCategory() != null ? experience.getCategory().getSlug() : null,
                experience.getCategories().stream().map(c -> c.getId()).toList(),
                experience.getCity().getId(),
                experience.getCity().getName(),
                experience.getCity().getSlug(),
                experience.getCity().getCountry(),
                experience.getTitle(),
                experience.getSlug(),
                experience.getDescription(),
                experience.getMeetingArea(),
                experience.getDurationMinutes(),
                experience.getPriceAmount(),
                experience.getCurrency(),
                experience.getMaxGuests(),
                experience.getLatitude(),
                experience.getLongitude(),
                experience.getBookingMode(),
                experience.getPrivatePrice(),
                experience.getPriceNetAmount(),
                experience.getPriceInputMode(),
                experience.getSafetyNotes(),
                experience.getShortDescription(),
                experience.getTransportMode(),
                experience.getInclusions(),
                experience.getExclusions(),
                experience.getEndLocation(),
                experience.getReasonsToBook(),
                experience.getMinimumAge(),
                experience.getStatus(),
                experience.getCreatedAt(),
                experience.getUpdatedAt(),
                experience.getExternalListingType(),
                experience.getExternalListingDetails()
        );
    }

    private String generateSlug(String title) {
        String baseSlug = Normalizer.normalize(requiredTrim(title), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        return baseSlug + "-" + System.currentTimeMillis();
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        if (value == null) {
            throw new BadRequestException("Amount is required");
        }

        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Amount cannot be negative");
        }

        return value;
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

    private String requiredTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("Required value is missing");
        }
        return value.trim();
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}