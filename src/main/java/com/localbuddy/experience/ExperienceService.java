package com.localbuddy.experience;

import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.ConsentService;
import com.localbuddy.localprofile.LocalApprovalStatus;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.media.ExperiencePhoto;
import com.localbuddy.media.ExperiencePhotoRepository;
import com.localbuddy.pricing.VatService;
import com.localbuddy.trustsafety.TrustSafetyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final VatService vatService;
    private final BookingRepository bookingRepository;
    private final ExperiencePhotoRepository experiencePhotoRepository;
    /** Hard cap admins can assign (app.platform.max-commission-rate); guards host payouts. */
    private final BigDecimal maxCommissionRate;
    /** Zone for building day windows in the availability date filter (fallback when a city has none). */
    private final ZoneId defaultZone;

    public ExperienceService(ExperienceRepository experienceRepository,
                             ExperienceCategoryRepository categoryRepository,
                             CityRepository cityRepository,
                             LocalProfileRepository localProfileRepository,
                             ConsentService consentService,
                             TrustSafetyService trustSafetyService,
                             VatService vatService,
                             BookingRepository bookingRepository,
                             ExperiencePhotoRepository experiencePhotoRepository,
                             @Value("${app.platform.max-commission-rate:0.50}") BigDecimal maxCommissionRate,
                             @Value("${app.platform.default-timezone:Europe/Amsterdam}") String defaultTimezone) {
        this.experienceRepository = experienceRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.localProfileRepository = localProfileRepository;
        this.consentService = consentService;
        this.trustSafetyService = trustSafetyService;
        this.vatService = vatService;
        this.bookingRepository = bookingRepository;
        this.experiencePhotoRepository = experiencePhotoRepository;
        this.maxCommissionRate = maxCommissionRate;
        this.defaultZone = ZoneId.of(defaultTimezone);
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

        return toResponseList(experienceRepository.findByLocalProfileId(localProfile.getId()));
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

    /** Unpublish (pause) a live experience so it no longer appears in the catalog. Reversible. */
    @Transactional
    public ExperienceResponse unpublishMyExperience(UUID userId, UUID experienceId) {
        Experience experience = requireOwnedExperience(userId, experienceId);
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new BadRequestException("Only a published (approved) experience can be unpublished");
        }
        experience.setStatus(ExperienceStatus.PAUSED);
        return toResponse(experienceRepository.save(experience));
    }

    /** Re-publish a paused experience so it appears in the catalog again. */
    @Transactional
    public ExperienceResponse publishMyExperience(UUID userId, UUID experienceId) {
        Experience experience = requireOwnedExperience(userId, experienceId);
        if (experience.getStatus() != ExperienceStatus.PAUSED) {
            throw new BadRequestException("Only a paused experience can be re-published");
        }
        experience.setStatus(ExperienceStatus.APPROVED);
        return toResponse(experienceRepository.save(experience));
    }

    /** Soft-delete (archive) an experience. Existing bookings keep referencing it; it's hidden everywhere. */
    @Transactional
    public void deleteMyExperience(UUID userId, UUID experienceId) {
        Experience experience = requireOwnedExperience(userId, experienceId);
        if (experience.getStatus() == ExperienceStatus.BLOCKED) {
            throw new BadRequestException("A blocked experience cannot be deleted");
        }
        experience.setStatus(ExperienceStatus.ARCHIVED);
        experienceRepository.save(experience);
    }

    /** Loads an experience and asserts the authenticated user owns it (404 otherwise). */
    private Experience requireOwnedExperience(UUID userId, UUID experienceId) {
        LocalProfile localProfile = getLocalProfileByUserId(userId);
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Experience not found");
        }
        return experience;
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
            BookingMode bookingMode,
            Boolean shared,
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
        Integer maxMinimumAge;
        if (infantCount > 0) {
            maxMinimumAge = 0;
        } else if (childCount > 0) {
            maxMinimumAge = 2;
        } else if (teenCount > 0) {
            maxMinimumAge = 13;
        } else {
            maxMinimumAge = null;
        }

        Instant dateStart = null;
        Instant dateEnd = null;
        if (date != null) {
            dateStart = date.atStartOfDay(defaultZone).toInstant();
            dateEnd = date.plusDays(1).atStartOfDay(defaultZone).toInstant();
        }

        int pageNumber = Math.max(0, page);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        boolean filterByDate = dateStart != null;
        boolean filterByAvailability = guests != null || dateStart != null;

        Collection<BookingMode> bookingModes = resolveBookingModes(bookingMode, shared);
        Page<Experience> result = experienceRepository.searchApproved(
                city, category, bookingModes, maxMinimumAge, guests, Instant.now(), dateStart, dateEnd,
                filterByAvailability, filterByDate, pageable);

        List<ExperienceResponse> content = toResponseList(result.getContent());

        return new ExperiencePageResponse(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public ExperiencePageResponse advancedSearch(
            String citySlug,
            String categorySlug,
            BookingMode bookingMode,
            Boolean shared,
            LocalDate date,
            Integer adults,
            Integer teens,
            Integer children,
            Integer infants,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Integer maxDurationMinutes,
            BigDecimal minHostRating,
            String keyword,
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

        Integer maxMinimumAge;
        if (infantCount > 0) {
            maxMinimumAge = 0;
        } else if (childCount > 0) {
            maxMinimumAge = 2;
        } else if (teenCount > 0) {
            maxMinimumAge = 13;
        } else {
            maxMinimumAge = null;
        }

        Instant dateStart = null;
        Instant dateEnd = null;
        if (date != null) {
            dateStart = date.atStartOfDay(defaultZone).toInstant();
            dateEnd = date.plusDays(1).atStartOfDay(defaultZone).toInstant();
        }

        BigDecimal normalizedMinPrice = normalizePrice(minPrice);
        BigDecimal normalizedMaxPrice = normalizePrice(maxPrice);
        Integer normalizedMaxDuration = (maxDurationMinutes != null && maxDurationMinutes > 0)
                ? maxDurationMinutes : null;
        BigDecimal normalizedMinRating = (minHostRating != null && minHostRating.signum() > 0)
                ? minHostRating : null;

        String keywordPattern = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            keywordPattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        }

        int pageNumber = Math.max(0, page);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        boolean filterByDate = dateStart != null;
        boolean filterByAvailability = guests != null || dateStart != null;

        Collection<BookingMode> bookingModes = resolveBookingModes(bookingMode, shared);
        Page<Experience> result = experienceRepository.searchApprovedAdvanced(
                city, category, bookingModes, maxMinimumAge, guests, Instant.now(), dateStart, dateEnd,
                normalizedMinPrice, normalizedMaxPrice, normalizedMaxDuration, normalizedMinRating,
                keywordPattern, filterByAvailability, filterByDate, pageable);

        List<ExperienceResponse> content = toResponseList(result.getContent());

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
        applyPricing(experience, request.priceAmount(), request.priceInputMode());
        experience.setCurrency(requiredTrim(request.currency()).toUpperCase(Locale.ROOT));
        experience.setMaxGuests(request.maxGuests());
        applyCoordinates(experience, request.latitude(), request.longitude());
        experience.setSafetyNotes(optionalTrim(request.safetyNotes()));
        experience.setShortDescription(optionalTrim(request.shortDescription()));
        experience.setTransportMode(request.transportMode());
        experience.setInclusions(optionalTrim(request.inclusions()));
        experience.setExclusions(optionalTrim(request.exclusions()));
        experience.setEndLocation(optionalTrim(request.endLocation()));
        experience.setReasonsToBook(optionalTrim(request.reasonsToBook()));
        experience.setMinimumAge(request.minimumAge() == null ? 0 : request.minimumAge());
        applyBookingMode(experience, request.bookingMode(), request.privatePrice(),
                request.externalListingType(), request.externalListingDetails());
        applyCategories(experience, request.categoryIds());
    }

    private void applyUpdateRequest(Experience experience, UpdateExperienceRequest request) {
        experience.setTitle(requiredTrim(request.title()));
        experience.setDescription(requiredTrim(request.description()));
        experience.setMeetingArea(optionalTrim(request.meetingArea()));
        experience.setDurationMinutes(request.durationMinutes());
        applyPricing(experience, request.priceAmount(), request.priceInputMode());
        experience.setCurrency(requiredTrim(request.currency()).toUpperCase(Locale.ROOT));
        experience.setMaxGuests(request.maxGuests());
        applyCoordinates(experience, request.latitude(), request.longitude());
        experience.setSafetyNotes(optionalTrim(request.safetyNotes()));
        experience.setShortDescription(optionalTrim(request.shortDescription()));
        experience.setTransportMode(request.transportMode());
        experience.setInclusions(optionalTrim(request.inclusions()));
        experience.setExclusions(optionalTrim(request.exclusions()));
        experience.setEndLocation(optionalTrim(request.endLocation()));
        experience.setReasonsToBook(optionalTrim(request.reasonsToBook()));
        experience.setMinimumAge(request.minimumAge() == null ? 0 : request.minimumAge());
        applyBookingMode(experience, request.bookingMode(), request.privatePrice(),
                request.externalListingType(), request.externalListingDetails());
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

    private void applyBookingMode(Experience experience, BookingMode bookingMode, BigDecimal privatePrice,
                                   ExternalListingType externalListingType, String externalListingDetails) {
        BookingMode mode = bookingMode == null ? BookingMode.SHARED : bookingMode;
        ExternalListingType listingType =
                externalListingType == null ? ExternalListingType.NONE : externalListingType;

        // Experiences listed on a third-party aggregator (Airbnb, Viator, GetYourGuide, etc.) cannot offer
        // private-buyout bookings because LocalBuddy cannot guarantee slot exclusivity when external
        // bookings exist. Own website / social media (and not-listed) do not trigger this rule.
        if (listingType.blocksPrivateBooking() && mode != BookingMode.SHARED) {
            throw new BadRequestException(
                    "Experiences listed on an external aggregator platform can only offer shared bookings. " +
                    "Set booking mode to Shared, or change the external listing option.");
        }

        // PRIVATE_ONLY: host sets a flat total price — per-person priceAmount is not required.
        // PRIVATE_ALLOWED: host sets a flat private price charged as-is (no discount).
        BigDecimal normalizedPrivatePrice = normalizePrice(privatePrice);

        if (mode == BookingMode.PRIVATE_ONLY && normalizedPrivatePrice == null) {
            throw new BadRequestException("A flat private price is required for private-only experiences");
        }
        if (mode == BookingMode.PRIVATE_ALLOWED && normalizedPrivatePrice == null) {
            throw new BadRequestException("A private price is required when private booking is allowed");
        }
        // For SHARED or PRIVATE_ALLOWED, a per-person priceAmount must have been set by applyPricing.
        if (mode != BookingMode.PRIVATE_ONLY && experience.getPriceAmount() == null) {
            throw new BadRequestException("Price per person is required for shared and private-allowed experiences");
        }

        // When both shared and private are offered, the flat private (whole-slot) price may not exceed
        // the full shared price for a sold-out slot: maxGuests × per-person price.
        if (mode == BookingMode.PRIVATE_ALLOWED) {
            BigDecimal cap = experience.getPriceAmount()
                    .multiply(BigDecimal.valueOf(experience.getMaxGuests()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (normalizedPrivatePrice.compareTo(cap) > 0) {
                throw new BadRequestException(
                        "The private price cannot exceed maximum guests × per-person price (" + cap + ")");
            }
        }

        experience.setExternalListingType(listingType);
        experience.setExternalListingDetails(optionalTrim(externalListingDetails));
        experience.setBookingMode(mode);
        experience.setPrivatePrice(normalizedPrivatePrice);
    }

    /**
     * Stores both the gross (customer-facing) and net price. The host enters one
     * (per priceInputMode); the other is derived from the experience's VAT rate.
     */
    private void applyPricing(Experience experience, BigDecimal enteredPrice, PriceInputMode mode) {
        PriceInputMode inputMode = mode == null ? PriceInputMode.GROSS : mode;
        BigDecimal entered = normalizePrice(enteredPrice);
        experience.setPriceInputMode(inputMode);

        if (entered == null) {
            experience.setPriceAmount(null);
            experience.setPriceNetAmount(null);
            return;
        }

        BigDecimal vatRate = vatService.experienceVatRate(
                experience, vatService.placeOfSupply(experience, experience.getLocalProfile()), Instant.now());
        BigDecimal onePlus = BigDecimal.ONE.add(vatRate);

        if (inputMode == PriceInputMode.NET) {
            experience.setPriceNetAmount(entered);
            experience.setPriceAmount(entered.multiply(onePlus).setScale(2, java.math.RoundingMode.HALF_UP));
        } else {
            experience.setPriceAmount(entered);
            experience.setPriceNetAmount(entered.divide(onePlus, 2, java.math.RoundingMode.HALF_UP));
        }
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
        var coverPhoto = experiencePhotoRepository.findCoverPhotoByExperienceId(experience.getId());
        var coverImage = coverPhoto.map(photo ->
                new ExperienceResponse.CoverImage(photo.getId(), photo.getUrl(), photo.getCaption())
        ).orElse(null);
        return toResponse(experience, coverImage);
    }

    /**
     * Maps a page/list of experiences, resolving all cover photos in ONE batched query instead of
     * one lookup per row (the list-mapping N+1). Output is identical to mapping each experience with
     * {@link #toResponse(Experience)} — same CoverImage per row, absent cover → null.
     */
    private List<ExperienceResponse> toResponseList(List<Experience> experiences) {
        if (experiences.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = experiences.stream().map(Experience::getId).toList();
        Map<UUID, ExperienceResponse.CoverImage> coversByExperienceId = new HashMap<>();
        for (com.localbuddy.media.ExperiencePhoto photo : experiencePhotoRepository.findCoverPhotosByExperienceIds(ids)) {
            coversByExperienceId.putIfAbsent(
                    photo.getExperience().getId(),
                    new ExperienceResponse.CoverImage(photo.getId(), photo.getUrl(), photo.getCaption()));
        }
        return experiences.stream()
                .map(e -> toResponse(e, coversByExperienceId.get(e.getId())))
                .toList();
    }

    private ExperienceResponse toResponse(Experience experience, ExperienceResponse.CoverImage coverImage) {
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
                experience.getExternalListingDetails(),
                experience.getCommissionRate(),
                coverImage
        );
    }


    @Transactional(readOnly = true)
    public List<ExperienceResponse> getPendingExperiences() {
        return toResponseList(experienceRepository.findByStatus(ExperienceStatus.SUBMITTED));
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

    /**
     * Set or clear an experience's per-experience commission override (admin only).
     * {@code commissionRate == null} clears the override so the host/rule/default
     * rate applies again. The override supersedes every commission rule for this
     * experience, so it is capped at {@code app.platform.max-commission-rate}.
     */
    @Transactional
    public ExperienceResponse setCommissionOverride(UUID experienceId, BigDecimal commissionRate) {
        validateCommissionOverrideRate(commissionRate);
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        experience.setCommissionRate(commissionRate);
        return toResponse(experienceRepository.save(experience));
    }

    /**
     * Bulk variant of {@link #setCommissionOverride}: same rate (or clear) applied to every
     * listed experience atomically — one unknown id fails the whole batch so a partial apply
     * can't go unnoticed.
     */
    @Transactional
    public List<ExperienceResponse> setCommissionOverrideBulk(List<UUID> experienceIds, BigDecimal commissionRate) {
        validateCommissionOverrideRate(commissionRate);
        List<UUID> ids = experienceIds.stream().distinct().toList();
        List<Experience> experiences = experienceRepository.findAllById(ids);
        if (experiences.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more experiences were not found");
        }
        experiences.forEach(e -> e.setCommissionRate(commissionRate));
        return toResponseList(experienceRepository.saveAll(experiences));
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
        return getApprovedExperiences(citySlug, categorySlug, null, null);
    }

    @Transactional(readOnly = true)
    public List<ExperienceResponse> getApprovedExperiences(String citySlug, String categorySlug,
                                                           BookingMode bookingMode, Boolean shared) {
        Collection<BookingMode> bookingModes = resolveBookingModes(bookingMode, shared);
        return toResponseList(findApprovedExperiences(citySlug, categorySlug, bookingModes));
    }

    @Transactional(readOnly = true)
    public List<ExperienceResponse> getTrendingExperiences(String citySlug, Integer windowDays, Integer limit) {
        int days = (windowDays == null || windowDays <= 0) ? 30 : Math.min(windowDays, 365);
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Map<UUID, Long> recentBookings = new HashMap<>();
        for (Object[] row : bookingRepository.countBookingsByExperienceSince(
                List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED), since)) {
            recentBookings.put((UUID) row[0], (Long) row[1]);
        }

        List<Experience> approved = new ArrayList<>(findApprovedExperiences(citySlug, null, null));

        Comparator<Experience> byTrending = Comparator
                .comparingLong((Experience e) -> recentBookings.getOrDefault(e.getId(), 0L))
                .reversed()
                .thenComparing(ExperienceService::hostRating,
                        Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(Experience::getCreatedAt,
                        Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
        approved.sort(byTrending);

        return toResponseList(approved.stream().limit(max).toList());
    }

    private static BigDecimal hostRating(Experience e) {
        if (e.getLocalProfile() == null || e.getLocalProfile().getUser() == null) {
            return null;
        }
        return e.getLocalProfile().getUser().getRatingAvg();
    }

    /**
     * Approved experiences that have coordinates, as lightweight map markers. When a viewer location
     * (lat,lng) is supplied, each marker gets a distanceKm and the list is sorted nearest-first
     * (optionally filtered to within radiusKm).
     */
    @Transactional(readOnly = true)
    public List<ExperienceMapMarker> getMapMarkers(String citySlug, String categorySlug,
                                                   Double lat, Double lng, Double radiusKm) {
        boolean hasOrigin = lat != null && lng != null;
        List<ExperienceMapMarker> markers = new java.util.ArrayList<>();

        for (Experience e : findApprovedExperiences(citySlug, categorySlug, null)) {
            if (e.getLatitude() == null || e.getLongitude() == null) {
                continue;
            }
            Double distanceKm = null;
            if (hasOrigin) {
                double km = haversineKm(lat, lng, e.getLatitude().doubleValue(), e.getLongitude().doubleValue());
                distanceKm = Math.round(km * 100.0) / 100.0;
            }
            markers.add(new ExperienceMapMarker(e.getId(), e.getSlug(), e.getTitle(),
                    e.getLatitude(), e.getLongitude(), e.getPriceAmount(), e.getCurrency(),
                    e.getCity().getName(), distanceKm));
        }

        if (hasOrigin) {
            if (radiusKm != null) {
                markers.removeIf(m -> m.distanceKm() != null && m.distanceKm() > radiusKm);
            }
            markers.sort(java.util.Comparator.comparingDouble(
                    m -> m.distanceKm() == null ? Double.MAX_VALUE : m.distanceKm()));
        }
        return markers;
    }

    private List<Experience> findApprovedExperiences(String citySlug, String categorySlug,
                                                     Collection<BookingMode> bookingModes) {
        String city = normalizeSlug(citySlug);
        String category = normalizeSlug(categorySlug);
        return experienceRepository.findApprovedForListing(city, category, bookingModes);
    }

    private Collection<BookingMode> resolveBookingModes(BookingMode bookingMode, Boolean shared) {
        if (bookingMode != null) {
            return List.of(bookingMode);
        }
        if (Boolean.TRUE.equals(shared)) {
            return List.of(BookingMode.SHARED);
        }
        if (Boolean.FALSE.equals(shared)) {
            return List.of(BookingMode.PRIVATE_ALLOWED, BookingMode.PRIVATE_ONLY);
        }
        return null;
    }

    private void applyCoordinates(Experience experience, BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new BadRequestException("Latitude and longitude must be provided together");
        }
        experience.setLatitude(latitude);
        experience.setLongitude(longitude);
    }

    /** Great-circle distance in km (Haversine). */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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