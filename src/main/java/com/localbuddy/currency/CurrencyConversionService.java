package com.localbuddy.currency;

import com.localbuddy.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Performs currency conversion using rates fetched from an {@link ExchangeRateProvider},
 * backed by a thread-safe in-memory cache with a configurable TTL.
 *
 * <p>On provider failure a stale cache entry (if any) is served; otherwise a
 * {@link BadRequestException} is raised.
 */
@Service
public class CurrencyConversionService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyConversionService.class);
    private static final int RESULT_SCALE = 2;

    private final ExchangeRateProvider exchangeRateProvider;
    private final Set<String> supportedCurrencies;
    private final Duration cacheTtl;

    /** Cache keyed by upper-cased base currency. */
    private final ConcurrentHashMap<String, CachedRates> cache = new ConcurrentHashMap<>();

    public CurrencyConversionService(
            ExchangeRateProvider exchangeRateProvider,
            @Value("${app.currency.supported:EUR,USD,GBP}") String supported,
            @Value("${app.currency.cache-ttl-seconds:3600}") long cacheTtlSeconds) {
        this.exchangeRateProvider = exchangeRateProvider;
        this.supportedCurrencies = Arrays.stream(supported.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
    }

    /**
     * Convert {@code amount} from one currency to another.
     *
     * @return the converted amount scaled to 2 decimal places, rounded HALF_UP.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            throw new BadRequestException("Amount is required");
        }
        String base = normalizeAndValidate(from);
        String target = normalizeAndValidate(to);

        if (base.equals(target)) {
            return amount.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal rate = rate(base, target);
        return amount.multiply(rate).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Return the single conversion rate from {@code base} to {@code target}.
     */
    public BigDecimal rate(String base, String target) {
        String normalizedBase = normalizeAndValidate(base);
        String normalizedTarget = normalizeAndValidate(target);
        if (normalizedBase.equals(normalizedTarget)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = getRates(normalizedBase).get(normalizedTarget);
        if (rate == null) {
            throw new BadRequestException("Rate unavailable for " + normalizedBase + "->" + normalizedTarget);
        }
        return rate;
    }

    /**
     * Return the current rate map for the given base currency (target code -> rate).
     */
    public Map<String, BigDecimal> getRates(String base) {
        String normalizedBase = normalizeAndValidate(base);
        return Collections.unmodifiableMap(loadRates(normalizedBase));
    }

    /**
     * Resolve rates for a base currency, using the cache when fresh and falling
     * back to a stale entry on provider failure.
     */
    private Map<String, BigDecimal> loadRates(String base) {
        CachedRates cached = cache.get(base);
        if (cached != null && !cached.isExpired(cacheTtl)) {
            return cached.rates();
        }

        try {
            Map<String, BigDecimal> fresh = exchangeRateProvider.fetchLatestRates(base, supportedCurrencies);
            CachedRates refreshed = new CachedRates(Map.copyOf(fresh), Instant.now());
            cache.put(base, refreshed);
            return refreshed.rates();
        } catch (RuntimeException ex) {
            if (cached != null) {
                log.warn("Currency provider failed for base={}, serving stale cache: {}", base, ex.getMessage());
                return cached.rates();
            }
            log.error("Currency provider failed for base={} and no cache available", base, ex);
            throw new BadRequestException("Currency rates unavailable");
        }
    }

    private String normalizeAndValidate(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Currency code is required");
        }
        String normalized = code.trim().toUpperCase();
        if (!supportedCurrencies.contains(normalized)) {
            throw new BadRequestException("Unsupported currency: " + code);
        }
        return normalized;
    }

    /** Immutable cache entry pairing a rate snapshot with the time it was fetched. */
    private record CachedRates(Map<String, BigDecimal> rates, Instant fetchedAt) {
        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
