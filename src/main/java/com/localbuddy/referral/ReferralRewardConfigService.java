package com.localbuddy.referral;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the effective referral reward (amount, currency, monthly cap) from the
 * single admin-managed {@link ReferralRewardConfig}, falling back to the built-in
 * {@code app.referral.*} defaults, and provides the admin CRUD for that singleton.
 */
@Service
public class ReferralRewardConfigService {

    private final ReferralRewardConfigRepository repository;
    private final BigDecimal defaultRewardAmount;
    private final String defaultRewardCurrency;
    private final int defaultMonthlyCap;

    public ReferralRewardConfigService(
            ReferralRewardConfigRepository repository,
            @Value("${app.referral.default-reward-amount:20}") BigDecimal defaultRewardAmount,
            @Value("${app.referral.reward-currency:EUR}") String defaultRewardCurrency,
            @Value("${app.referral.default-max-monthly-redemptions:5}") int defaultMonthlyCap) {
        this.repository = repository;
        this.defaultRewardAmount = defaultRewardAmount.setScale(2, RoundingMode.HALF_UP);
        this.defaultRewardCurrency = defaultRewardCurrency.trim().toUpperCase(Locale.ROOT);
        this.defaultMonthlyCap = defaultMonthlyCap;
    }

    private Optional<ReferralRewardConfig> findConfig() {
        return repository.findFirstByOrderByCreatedAtAsc();
    }

    /** Reward amount in force at {@code now}: the config's when active + inside its window, else the default. */
    @Transactional(readOnly = true)
    public BigDecimal effectiveRewardAmount(Instant now) {
        return findConfig()
                .filter(c -> c.isEffectiveAt(now))
                .map(ReferralRewardConfig::getRewardAmount)
                .orElse(defaultRewardAmount)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public String effectiveRewardCurrency(Instant now) {
        return findConfig()
                .filter(c -> c.isEffectiveAt(now))
                .map(ReferralRewardConfig::getRewardCurrency)
                .orElse(defaultRewardCurrency);
    }

    /** Per-referrer monthly redemption cap: the active config's override, else the default. */
    @Transactional(readOnly = true)
    public int effectiveMonthlyCap() {
        return findConfig()
                .filter(ReferralRewardConfig::isActive)
                .map(ReferralRewardConfig::getMaxMonthlyRedemptions)
                .orElse(defaultMonthlyCap);
    }

    @Transactional(readOnly = true)
    public ReferralRewardConfigResponse getConfig() {
        return findConfig().map(this::toResponse).orElseGet(this::defaultsResponse);
    }

    @Transactional
    public ReferralRewardConfigResponse createConfig(ReferralRewardConfigRequest request) {
        if (findConfig().isPresent()) {
            throw new BadRequestException(
                    "A referral reward configuration already exists — edit or delete it (only one is allowed).");
        }
        ReferralRewardConfig config = new ReferralRewardConfig();
        apply(config, request);
        return toResponse(repository.save(config));
    }

    @Transactional
    public ReferralRewardConfigResponse updateConfig(ReferralRewardConfigRequest request) {
        ReferralRewardConfig config = requireConfig();
        apply(config, request);
        return toResponse(repository.save(config));
    }

    @Transactional
    public ReferralRewardConfigResponse setActive(boolean active) {
        ReferralRewardConfig config = requireConfig();
        config.setActive(active);
        return toResponse(repository.save(config));
    }

    @Transactional
    public void deleteConfig() {
        repository.delete(requireConfig());
    }

    private ReferralRewardConfig requireConfig() {
        return findConfig().orElseThrow(
                () -> new ResourceNotFoundException("No referral reward configuration exists"));
    }

    private void apply(ReferralRewardConfig config, ReferralRewardConfigRequest request) {
        if (request.startsAt() != null && request.endsAt() != null
                && request.endsAt().isBefore(request.startsAt())) {
            throw new BadRequestException("End date must be on or after the start date");
        }
        config.setRewardAmount(request.rewardAmount().setScale(2, RoundingMode.HALF_UP));
        config.setRewardCurrency(request.rewardCurrency() != null && !request.rewardCurrency().isBlank()
                ? request.rewardCurrency().trim().toUpperCase(Locale.ROOT)
                : defaultRewardCurrency);
        config.setStartsAt(request.startsAt());
        config.setEndsAt(request.endsAt());
        config.setMaxMonthlyRedemptions(request.maxMonthlyRedemptions());
        config.setActive(request.active() == null || request.active());
    }

    private ReferralRewardConfigResponse toResponse(ReferralRewardConfig c) {
        Instant now = Instant.now();
        boolean effectiveNow = c.isEffectiveAt(now);
        return new ReferralRewardConfigResponse(
                c.getId(),
                c.getRewardAmount(),
                c.getRewardCurrency(),
                c.getStartsAt(),
                c.getEndsAt(),
                c.getMaxMonthlyRedemptions(),
                c.isActive(),
                effectiveNow,
                effectiveNow ? c.getRewardAmount() : defaultRewardAmount,
                c.isActive() && c.getMaxMonthlyRedemptions() != null
                        ? c.getMaxMonthlyRedemptions() : defaultMonthlyCap,
                defaultRewardAmount,
                defaultMonthlyCap,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    /** Response shown when no custom config exists — the platform defaults are in force. */
    private ReferralRewardConfigResponse defaultsResponse() {
        return new ReferralRewardConfigResponse(
                null, defaultRewardAmount, defaultRewardCurrency, null, null, null,
                false, false, defaultRewardAmount, defaultMonthlyCap,
                defaultRewardAmount, defaultMonthlyCap, null, null);
    }
}
