package com.localbuddy.pricing;

import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.experience.ExperienceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only pricing facts safe to expose publicly, so the storefront can show
 * the real, admin-configured service fee instead of a hardcoded copy value.
 * Rates come from the same {@link ServiceFeeResolver} the checkout charge uses
 * — what these endpoints report is exactly what {@link PricingEngine} applies.
 */
@Service
public class PublicPricingService {

    private final ServiceFeeResolver serviceFeeResolver;
    private final VatService vatService;
    private final ExperienceRepository experienceRepository;

    public PublicPricingService(ServiceFeeResolver serviceFeeResolver,
                                VatService vatService,
                                ExperienceRepository experienceRepository) {
        this.serviceFeeResolver = serviceFeeResolver;
        this.vatService = vatService;
        this.experienceRepository = experienceRepository;
    }

    /** Platform-default service fee — what content pages (terms/help) display. */
    @Transactional(readOnly = true)
    public PublicServiceFeeResponse platformServiceFee() {
        Instant now = Instant.now();
        return toResponse(serviceFeeResolver.platformRate(now), now);
    }

    /**
     * Effective service fee for one approved experience — honours EXPERIENCE /
     * HOST / CATEGORY / CITY overrides (which may be 0), so the checkout preview
     * matches the eventual charge.
     */
    @Transactional(readOnly = true)
    public PublicServiceFeeResponse serviceFeeForExperience(UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.APPROVED) {
            throw new ResourceNotFoundException("Experience not found");
        }
        Instant now = Instant.now();
        return toResponse(serviceFeeResolver.resolve(experience, experience.getLocalProfile(), now), now);
    }

    private PublicServiceFeeResponse toResponse(BigDecimal feeRate, Instant now) {
        return new PublicServiceFeeResponse(
                toPct(feeRate),
                toPct(vatService.feeVatRate(now)));
    }

    /** Fraction (0.0250) → percent (2.50), the shape the storefront renders. */
    private static BigDecimal toPct(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }
}
