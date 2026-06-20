package com.localbuddy.currency;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction over an external exchange-rate data source.
 */
public interface ExchangeRateProvider {

    /**
     * Fetch the latest exchange rates for the given base currency over the requested symbols.
     *
     * @param base    ISO 4217 base currency code (e.g. {@code EUR})
     * @param symbols the target currency codes to retrieve rates for
     * @return a map of target currency code -> rate relative to {@code base}.
     * The map will not contain the base currency itself.
     */
    Map<String, BigDecimal> fetchLatestRates(String base, Set<String> symbols);
}
