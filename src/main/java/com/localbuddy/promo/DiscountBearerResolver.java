package com.localbuddy.promo;

import com.localbuddy.experience.Experience;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Resolves who bears the cost of a discount (promo, deal) based on a three-level precedence:
 * 1. Promo's explicit bearer override (if set)
 * 2. Experience's override (if set)
 * 3. Host's default policy
 * 4. Platform default (HOST)
 *
 * This ensures discounts can be customized at the most granular level, with sensible fallbacks.
 */
@Service
public class DiscountBearerResolver {

    /**
     * Resolves the discount bearer for a promo applied to an experience.
     *
     * @param promo the promo code (may have explicit bearer/percentage overrides)
     * @param experience the target experience (may have overrides; its host policy is the fallback)
     * @return the resolved bearer type
     */
    public DiscountBearer resolveBearer(PromoCode promo, Experience experience) {
        // Level 1: promo's explicit override
        if (promo.getDiscountBearer() != null) {
            return promo.getDiscountBearer();
        }

        // Level 2: experience's override
        if (experience.getDiscountBearer() != null) {
            return experience.getDiscountBearer();
        }

        // Level 3: host's default policy
        if (experience.getLocalProfile() != null && experience.getLocalProfile().getDefaultDiscountBearer() != null) {
            return experience.getLocalProfile().getDefaultDiscountBearer();
        }

        // Level 4: platform default
        return DiscountBearer.HOST;
    }

    /**
     * Resolves the platform's share percentage for a SPLIT-type discount.
     *
     * @param promo the promo code (may have explicit share override)
     * @param experience the target experience (may have overrides; its host policy is the fallback)
     * @return the percentage (0–100), or null if not a SPLIT or no share is set
     */
    public BigDecimal resolvePlatformSharePercentage(PromoCode promo, Experience experience) {
        // Level 1: promo's explicit override
        if (promo.getPlatformSharePercentage() != null) {
            return promo.getPlatformSharePercentage();
        }

        // Level 2: experience's override
        if (experience.getPlatformSharePercentage() != null) {
            return experience.getPlatformSharePercentage();
        }

        // Level 3: host's default
        if (experience.getLocalProfile() != null && experience.getLocalProfile().getDefaultPlatformSharePercentage() != null) {
            return experience.getLocalProfile().getDefaultPlatformSharePercentage();
        }

        // No share specified (default to 0 for SPLIT, or irrelevant for non-SPLIT)
        return null;
    }

    /**
     * Combined resolution: returns both bearer and share in one call.
     */
    public DiscountBearerResolution resolve(PromoCode promo, Experience experience) {
        DiscountBearer bearer = resolveBearer(promo, experience);
        BigDecimal platformShare = resolvePlatformSharePercentage(promo, experience);
        return new DiscountBearerResolution(bearer, platformShare);
    }

    /**
     * DTO for resolved discount policy.
     */
    public static class DiscountBearerResolution {
        public final DiscountBearer bearer;
        public final BigDecimal platformSharePercentage;

        public DiscountBearerResolution(DiscountBearer bearer, BigDecimal platformSharePercentage) {
            this.bearer = bearer;
            this.platformSharePercentage = platformSharePercentage;
        }
    }
}
