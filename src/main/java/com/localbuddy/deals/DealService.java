package com.localbuddy.deals;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DealService {

    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);

    private final DealRepository dealRepository;
    private final ExperienceRepository experienceRepository;

    public DealService(DealRepository dealRepository, ExperienceRepository experienceRepository) {
        this.dealRepository = dealRepository;
        this.experienceRepository = experienceRepository;
    }

    @Transactional
    public DealResponse createDeal(CreateDealRequest request) {
        validateDealConfig(
                request.discountType(),
                request.discountValue(),
                request.scope(),
                request.targetCityId(),
                request.targetExperienceId(),
                request.targetCategoryId(),
                request.startsAt(),
                request.endsAt()
        );

        Deal deal = new Deal();
        deal.setName(request.name().trim());
        deal.setDescription(optionalTrim(request.description()));
        deal.setDealType(request.dealType());
        deal.setDiscountType(request.discountType());
        deal.setDiscountValue(request.discountValue());
        deal.setCurrency(optionalUpper(request.currency()));
        deal.setScope(request.scope());
        deal.setTargetCityId(request.targetCityId());
        deal.setTargetExperienceId(request.targetExperienceId());
        deal.setTargetCategoryId(request.targetCategoryId());
        deal.setStartsAt(request.startsAt());
        deal.setEndsAt(request.endsAt());
        deal.setPriority(request.priority() == null ? 0 : request.priority());
        deal.setBadgeText(optionalTrim(request.badgeText()));
        deal.setActive(request.active() == null || request.active());

        return toResponse(dealRepository.save(deal));
    }

    @Transactional
    public DealResponse updateDeal(UUID dealId, UpdateDealRequest request) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));

        validateDealConfig(
                request.discountType(),
                request.discountValue(),
                request.scope(),
                request.targetCityId(),
                request.targetExperienceId(),
                request.targetCategoryId(),
                request.startsAt(),
                request.endsAt()
        );

        deal.setName(request.name().trim());
        deal.setDescription(optionalTrim(request.description()));
        deal.setDealType(request.dealType());
        deal.setDiscountType(request.discountType());
        deal.setDiscountValue(request.discountValue());
        deal.setCurrency(optionalUpper(request.currency()));
        deal.setScope(request.scope());
        deal.setTargetCityId(request.targetCityId());
        deal.setTargetExperienceId(request.targetExperienceId());
        deal.setTargetCategoryId(request.targetCategoryId());
        deal.setStartsAt(request.startsAt());
        deal.setEndsAt(request.endsAt());
        deal.setPriority(request.priority() == null ? 0 : request.priority());
        deal.setBadgeText(optionalTrim(request.badgeText()));
        deal.setActive(request.active() == null || request.active());

        return toResponse(dealRepository.save(deal));
    }

    @Transactional(readOnly = true)
    public DealResponse getById(UUID dealId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));

        return toResponse(deal);
    }

    @Transactional(readOnly = true)
    public List<DealResponse> listAll() {
        return dealRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DealResponse deactivate(UUID dealId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));

        deal.setActive(false);

        return toResponse(dealRepository.save(deal));
    }

    @Transactional(readOnly = true)
    public List<DealResponse> listLiveDeals(DealType dealType,
                                            UUID cityId,
                                            UUID experienceId,
                                            UUID categoryId) {
        return dealRepository.findLiveDeals(Instant.now(), dealType, cityId, experienceId, categoryId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Resolves the single best currently-live deal for an approved experience and computes the
     * discounted price. Precedence: EXPERIENCE &gt; CATEGORY &gt; CITY &gt; GLOBAL, then higher priority,
     * then most recent. Returns null when no deal applies. Fixed-amount deals only apply when their
     * currency matches the experience currency.
     */
    @Transactional(readOnly = true)
    public ResolvedDealResponse resolveBestDealForExperience(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new ResourceNotFoundException("Experience not found");
        }

        BigDecimal price = experience.getPriceAmount();
        Deal best = resolveBestDeal(experience).orElse(null);
        if (best == null || price == null) {
            return null;
        }

        BigDecimal discount = computeDiscount(best, price);
        String currency = experience.getCurrency() == null ? null
                : experience.getCurrency().toUpperCase(Locale.ROOT);
        return new ResolvedDealResponse(
                best.getId(), best.getName(), best.getBadgeText(),
                best.getDiscountType(), best.getDiscountValue(),
                price, discount, price.subtract(discount), currency);
    }

    /**
     * Resolves the deal to apply to a booking of the given experience against a base amount.
     * Returns {@link AppliedDeal#none()} (zero discount) when no live deal applies.
     */
    @Transactional(readOnly = true)
    public AppliedDeal resolveDealForBooking(Experience experience, BigDecimal baseAmount) {
        if (experience == null || baseAmount == null || baseAmount.signum() <= 0) {
            return AppliedDeal.none();
        }
        return resolveBestDeal(experience)
                .map(deal -> new AppliedDeal(deal.getId(), computeDiscount(deal, baseAmount), deal.getBadgeText()))
                .orElse(AppliedDeal.none());
    }

    private java.util.Optional<Deal> resolveBestDeal(Experience experience) {
        UUID cityId = experience.getCity() == null ? null : experience.getCity().getId();
        UUID categoryId = experience.getCategory() == null ? null : experience.getCategory().getId();
        String currency = experience.getCurrency() == null ? null
                : experience.getCurrency().toUpperCase(Locale.ROOT);

        List<Deal> candidates =
                dealRepository.findApplicableForExperience(Instant.now(), experience.getId(), categoryId, cityId);

        Comparator<Deal> byBest = Comparator
                .comparingInt((Deal d) -> specificityRank(d.getScope()))
                .thenComparingInt(d -> -(d.getPriority() == null ? 0 : d.getPriority()))
                .thenComparing(Deal::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

        return candidates.stream()
                .filter(d -> isApplicable(d, currency))
                .min(byBest);
    }

    private boolean isApplicable(Deal deal, String experienceCurrency) {
        if (deal.getDiscountType() == DealDiscountType.FIXED_AMOUNT) {
            String dealCurrency = deal.getCurrency() == null ? null
                    : deal.getCurrency().toUpperCase(Locale.ROOT);
            return dealCurrency == null || dealCurrency.equals(experienceCurrency);
        }
        return true;
    }

    private int specificityRank(DealScope scope) {
        return switch (scope) {
            case EXPERIENCE -> 0;
            case CATEGORY -> 1;
            case CITY -> 2;
            case GLOBAL -> 3;
        };
    }

    private BigDecimal computeDiscount(Deal deal, BigDecimal price) {
        BigDecimal raw;
        if (deal.getDiscountType() == DealDiscountType.PERCENTAGE) {
            raw = price.multiply(deal.getDiscountValue())
                    .divide(MAX_PERCENTAGE, 2, RoundingMode.HALF_UP);
        } else {
            raw = deal.getDiscountValue();
        }
        if (raw.signum() < 0) {
            raw = BigDecimal.ZERO;
        }
        if (raw.compareTo(price) > 0) {
            raw = price;
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    private void validateDealConfig(DealDiscountType discountType,
                                    BigDecimal discountValue,
                                    DealScope scope,
                                    UUID targetCityId,
                                    UUID targetExperienceId,
                                    UUID targetCategoryId,
                                    Instant startsAt,
                                    Instant endsAt) {
        if (discountValue.signum() < 0) {
            throw new BadRequestException("Discount value cannot be negative");
        }

        if (discountType == DealDiscountType.PERCENTAGE &&
                discountValue.compareTo(MAX_PERCENTAGE) > 0) {
            throw new BadRequestException("Percentage discount cannot exceed 100");
        }

        switch (scope) {
            case CITY -> {
                if (targetCityId == null) {
                    throw new BadRequestException("Target city is required for a CITY scoped deal");
                }
            }
            case EXPERIENCE -> {
                if (targetExperienceId == null) {
                    throw new BadRequestException("Target experience is required for an EXPERIENCE scoped deal");
                }
            }
            case CATEGORY -> {
                if (targetCategoryId == null) {
                    throw new BadRequestException("Target category is required for a CATEGORY scoped deal");
                }
            }
            case GLOBAL -> {
                // No target required for global deals.
            }
        }

        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BadRequestException("End time must be after start time");
        }
    }

    private DealResponse toResponse(Deal deal) {
        return new DealResponse(
                deal.getId(),
                deal.getName(),
                deal.getDescription(),
                deal.getDealType(),
                deal.getDiscountType(),
                deal.getDiscountValue(),
                deal.getCurrency(),
                deal.getScope(),
                deal.getTargetCityId(),
                deal.getTargetExperienceId(),
                deal.getTargetCategoryId(),
                deal.getStartsAt(),
                deal.getEndsAt(),
                deal.isActive(),
                deal.getPriority(),
                deal.getBadgeText(),
                deal.getCreatedAt(),
                deal.getUpdatedAt()
        );
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String optionalUpper(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
