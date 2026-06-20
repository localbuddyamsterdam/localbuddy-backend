package com.localbuddy.deals;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DealService {

    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);

    private final DealRepository dealRepository;

    public DealService(DealRepository dealRepository) {
        this.dealRepository = dealRepository;
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
