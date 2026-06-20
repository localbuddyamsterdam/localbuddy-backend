package com.localbuddy.experience;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.ConsentService;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.trustsafety.TrustSafetyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ExperienceService {

    private final ExperienceRepository experienceRepository;
    private final ExperienceCategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final LocalProfileRepository localProfileRepository;
    private final ConsentService consentService;
    private final TrustSafetyService trustSafetyService;

    public ExperienceService(ExperienceRepository experienceRepository,
                             ExperienceCategoryRepository categoryRepository,
                             CityRepository cityRepository,
                             LocalProfileRepository localProfileRepository  , ConsentService consentService, TrustSafetyService trustSafetyService) {
        this.experienceRepository = experienceRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.localProfileRepository = localProfileRepository;
        this.consentService = consentService;
        this.trustSafetyService = trustSafetyService;
    }

    @Transactional
    public ExperienceResponse createMyExperience(UUID userId, CreateExperienceRequest request) {
        consentService.requireLocalConsents(userId);
        trustSafetyService.requireUserCanHost(userId);
        LocalProfile localProfile = getApprovedLocalProfileByUserId(userId);
        ExperienceCategory category = getActiveCategory(request.categoryId());
        City city = getActiveCity(request.cityId());

        Experience experience = new Experience();
        experience.setLocalProfile(localProfile);
        experience.setCategory(category);
        experience.setCity(city);
        applyCreateRequest(experience, request);
        experience.setSlug(generateUniqueSlug(request.title()));
        experience.setStatus(ExperienceStatus.DRAFT);

        return toResponse(experienceRepository.save(experience));
    }

    @Transactional(readOnly = true)
    public List<ExperienceResponse> getMyExperiences(UUID userId) {
        LocalProfile localProfile = getLocalProfileByUserId(userId);

        return experienceRepository.findByLocalProfileId(localProfile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExperienceResponse getMyExperienceById(UUID userId, UUID experienceId) {
        LocalProfile localProfile = getLocalProfileByUserId(userId);

        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Experience not found");
        }

        return toResponse(experience);
    }

    @Transactional
    public ExperienceResponse updateMyExperience(UUID userId, UUID experienceId, UpdateExperienceRequest request) {
        LocalProfile localProfile = getLocalProfileByUserId(userId);
        trustSafetyService.requireUserCanHost(userId);
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Experience not found");
        }

        if (experience.getStatus() == ExperienceStatus.BLOCKED) {
            throw new BadRequestException("Blocked experience cannot be updated");
        }

        ExperienceCategory category = getActiveCategory(request.categoryId());
        City city = getActiveCity(request.cityId());

        experience.setCategory(category);
        experience.setCity(city);
        applyUpdateRequest(experience, request);

        if (experience.getStatus() == ExperienceStatus.APPROVED) {
            experience.setStatus(ExperienceStatus.SUBMITTED);
        }

        return toResponse(experienceRepository.save(experience));
    }

    @Transactional
    public ExperienceResponse submitMyExperience(UUID userId, UUID experienceId) {
        LocalProfile localProfile = getApprovedLocalProfileByUserId(userId);
        trustSafetyService.requireUserCanHost(userId);

        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Experience not found");
        }

        if (experience.getStatus() == ExperienceStatus.BLOCKED) {
            throw new BadRequestException("Blocked experience cannot be submitted");
        }

        if (experience.getStatus() == ExperienceStatus.APPROVED) {
            throw new BadRequestException("Approved experience is already live");
        }

        experience.setStatus(ExperienceStatus.SUBMITTED);

        return toResponse(experienceRepository.save(experience));
    }

    private LocalProfile getApprovedLocalProfileByUserId(UUID userId) {
        LocalProfile localProfile = getLocalProfileByUserId(userId);

        if (localProfile.getApprovalStatus() != LocalApprovalStatus.APPROVED) {
            throw new BadRequestException("Local profile must be approved before creating experiences");
        }

        return localProfile;
    }

    private LocalProfile getLocalProfileByUserId(UUID userId) {
        return localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
    }

    private ExperienceCategory getActiveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }

        ExperienceCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BadRequestException("Invalid experience category"));

        if (!category.isActive()) {
            throw new BadRequestException("Experience category is inactive");
        }

        return category;
    }

    private City getActiveCity(UUID cityId) {
        if (cityId == null) {
            throw new BadRequestException("City is required");
        }

        City city = cityRepository.findById(cityId)
                .orElseThrow(() -> new BadRequestException("Invalid city"));

        if (!city.isActive()) {
            throw new BadRequestException("City is not available for new experiences");
        }

        return city;
    }


    @Transactional(readOnly = true)
    public ExperiencePageResponse searchApprovedExperiences(
            String citySlug,
            String categorySlug,
            LocalDate date,
            Integer adults,
            Integer teens,
            Integer children,
            Integer infants,
            int page,
            int size
    ) {
        String city = normalizeSlug(citySlug);
        String category = normalizeSlug(categorySlug);

        int adultCount = adults == null ? 0 : Math.max(0, adults);
        int teenCount = teens == null ? 0 : Math.max(0, teens);
        int childCount = children == null ? 0 : Math.max(0, children);
        int infantCount = infants == null ? 0 : Math.max(0, infants);

        int totalGuests = adultCount + teenCount + childCount + infantCount;
        Integer guests = totalGuests > 0 ? totalGuests : null;

        // Exclude experiences whose minimum age would bar the youngest requested band.
        Integer maxMinimumAge = infantCount > 0 ? 0
                : childCount > 0 ? 2
                : teenCount > 0 ? 13
                : null;

        Instant dateStart = null;
        Instant dateEnd = null;
        if (date != null) {
            dateStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            dateEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        int pageNumber = Math.max(0, page);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        Page<Experience> result = experienceRepository.searchApproved(
                city, category, maxMinimumAge, guests, Instant.now(), dateStart, dateEnd, pageable);

        List<ExperienceResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new ExperiencePageResponse(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return null;
        }
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private void applyCreateRequest(Experience experience, CreateExperienceRequest request) {
        experience.setTitle(requiredTrim(request.title()));
        experience.setDescription(requiredTrim(request.description()));
        experience.setMeetingArea(optionalTrim(request.meetingArea()));
        experience.setDurationMinutes(request.durationMinutes());
        experience.setPriceAmount(normalizePrice(request.priceAmount()));
        experience.setCurrency(requiredTrim(request.currency()).toUpperCase(Locale.ROOT));
        experience.setMaxGuests(request.maxGuests());
        experience.setSafetyNotes(optionalTrim(request.safetyNotes()));
        experience.setShortDescription(optionalTrim(request.shortDescription()));
        experience.setTransportMode(request.transportMode());
        experience.setInclusions(optionalTrim(request.inclusions()));
        experience.setExclusions(optionalTrim(request.exclusions()));
        experience.setEndLocation(optionalTrim(request.endLocation()));
        experience.setReasonsToBook(optionalTrim(request.reasonsToBook()));
        experience.setMinimumAge(request.minimumAge() == null ? 0 : request.minimumAge());
        applyBookingMode(experience, request.bookingMode(), request.privatePrice());
        applyCategories(experience, request.categoryIds());
    }

    private void applyUpdateRequest(Experience experience, UpdateExperienceRequest request) {
        experience.setTitle(requiredTrim(request.title()));
        experience.setDescription(requiredTrim(request.description()));
        experience.setMeetingArea(optionalTrim(request.meetingArea()));
        experience.setDurationMinutes(request.durationMinutes());
        experience.setPriceAmount(normalizePrice(request.priceAmount()));
        experience.setCurrency(requiredTrim(request.currency()).toUpperCase(Locale.ROOT));
        experience.setMaxGuests(request.maxGuests());
        experience.setSafetyNotes(optionalTrim(request.safetyNotes()));
        experience.setShortDescription(optionalTrim(request.shortDescription()));
        experience.setTransportMode(request.transportMode());
        experience.setInclusions(optionalTrim(request.inclusions()));
        experience.setExclusions(optionalTrim(request.exclusions()));
        experience.setEndLocation(optionalTrim(request.endLocation()));
        experience.setReasonsToBook(optionalTrim(request.reasonsToBook()));
        experience.setMinimumAge(request.minimumAge() == null ? 0 : request.minimumAge());
        applyBookingMode(experience, request.bookingMode(), request.privatePrice());
        applyCategories(experience, request.categoryIds());
    }

    private void applyCategories(Experience experience, Set<UUID> categoryIds) {
        Set<ExperienceCategory> resolved = new LinkedHashSet<>();

        if (categoryIds != null) {
            for (UUID categoryId : categoryIds) {
                if (categoryId == null) {
                    continue;
                }
                ExperienceCategory category = categoryRepository.findById(categoryId)
                        .orElseThrow(() -> new BadRequestException("Invalid experience category: " + categoryId));
                if (!category.isActive()) {
                    throw new BadRequestException("Experience category is inactive: " + category.getName());
                }
                resolved.add(category);
            }
        }

        experience.getCategories().clear();
        experience.getCategories().addAll(resolved);
    }

    private void applyBookingMode(Experience experience, BookingMode bookingMode, BigDecimal privatePrice) {
        BookingMode mode = bookingMode == null ? BookingMode.SHARED : bookingMode;
        BigDecimal normalizedPrivatePrice = normalizePrice(privatePrice);

        if (mode != BookingMode.SHARED && normalizedPrivatePrice == null) {
            throw new BadRequestException("Private price is required when private booking is allowed");
        }

        experience.setBookingMode(mode);
        experience.setPrivatePrice(normalizedPrivatePrice);
    }

    private BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }

        return price.setScale(2, java.math.RoundingMode.HALF_UP);
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

    private String generateUniqueSlug(String title) {
        String baseSlug = slugify(title);
        String candidate = baseSlug;
        int counter = 1;

        while (experienceRepository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + counter;
            counter++;
        }

        return candidate;
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private ExperienceResponse toResponse(Experience experience) {
        return new ExperienceResponse(
                experience.getId(),
                experience.getLocalProfile().getId(),
                experience.getCategory() != null ? experience.getCategory().getId() : null,
                experience.getCategory() != null ? experience.getCategory().getName() : null,
                experience.getCategory() != null ? experience.getCategory().getSlug() : null,
                experience.getCategories().stream().map(ExperienceCategory::getId).toList(),
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
                experience.getBookingMode(),
                experience.getPrivatePrice(),
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
                experience.getUpdatedAt()
        );
    }


    @Transactional(readOnly = true)
    public List<ExperienceResponse> getPendingExperiences() {
        return experienceRepository.findByStatus(ExperienceStatus.SUBMITTED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExperienceResponse approveExperience(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (experience.getStatus() == ExperienceStatus.BLOCKED) {
            throw new BadRequestException("Blocked experience cannot be approved");
        }

        experience.setStatus(ExperienceStatus.APPROVED);

        return toResponse(experienceRepository.save(experience));
    }

    @Transactional
    public ExperienceResponse rejectExperience(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (experience.getStatus() == ExperienceStatus.BLOCKED) {
            throw new BadRequestException("Blocked experience cannot be rejected");
        }

        experience.setStatus(ExperienceStatus.REJECTED);

        return toResponse(experienceRepository.save(experience));
    }

    @Transactional(readOnly = true)
    public List<ExperienceResponse> getApprovedExperiences(String citySlug, String categorySlug) {
        String normalizedCitySlug = optionalTrim(citySlug);
        String normalizedCategorySlug = optionalTrim(categorySlug);

        if (normalizedCitySlug != null) {
            normalizedCitySlug = normalizedCitySlug.toLowerCase(Locale.ROOT);
        }

        if (normalizedCategorySlug != null) {
            normalizedCategorySlug = normalizedCategorySlug.toLowerCase(Locale.ROOT);
        }

        if (normalizedCitySlug != null && normalizedCategorySlug != null) {
            return experienceRepository
                    .findByCity_SlugAndCategory_SlugAndStatus(
                            normalizedCitySlug,
                            normalizedCategorySlug,
                            ExperienceStatus.APPROVED
                    )
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        if (normalizedCitySlug != null) {
            return experienceRepository
                    .findByCity_SlugAndStatus(normalizedCitySlug, ExperienceStatus.APPROVED)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        if (normalizedCategorySlug != null) {
            return experienceRepository
                    .findByCategory_SlugAndStatus(normalizedCategorySlug, ExperienceStatus.APPROVED)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return experienceRepository.findByStatus(ExperienceStatus.APPROVED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExperienceResponse getApprovedExperienceById(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new ResourceNotFoundException("Experience not found");
        }

        return toResponse(experience);
    }

    @Transactional(readOnly = true)
    public ExperienceResponse getApprovedExperienceBySlug(String slug) {
        Experience experience = experienceRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new ResourceNotFoundException("Experience not found");
        }

        return toResponse(experience);
    }
}