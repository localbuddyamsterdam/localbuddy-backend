package com.localbuddy.currency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default {@link ExchangeRateProvider} backed by the free Frankfurter API
 * (no API key required): {@code https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD,GBP}.
 */
@Component
public class FrankfurterExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterExchangeRateProvider.class);

    private final RestClient restClient;

    public FrankfurterExchangeRateProvider(
            @Value("${app.currency.provider-base-url:https://api.frankfurter.dev/v1}") String providerBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(providerBaseUrl)
                .build();
    }

    @Override
    public Map<String, BigDecimal> fetchLatestRates(String base, Set<String> symbols) {
        // Symbols excluding the base itself (Frankfurter rejects/ignores base in symbols).
        String symbolsParam = symbols.stream()
                .filter(s -> !s.equalsIgnoreCase(base))
                .collect(Collectors.joining(","));

        FrankfurterLatestResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/latest")
                        .queryParam("base", base)
                        .queryParam("symbols", symbolsParam)
                        .build())
                .retrieve()
                .body(FrankfurterLatestResponse.class);

        if (response == null || response.rates() == null) {
            log.warn("Frankfurter API returned empty body for base={}", base);
            throw new IllegalStateException("Empty response from exchange rate provider");
        }

        Map<String, BigDecimal> rates = new HashMap<>();
        response.rates().forEach((code, rate) -> rates.put(code.toUpperCase(), rate));
        return rates;
    }

    /**
     * Mirrors the Frankfurter response shape: {@code { "base": "EUR", "date": "...", "rates": { "USD": 1.08 } }}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrankfurterLatestResponse(String base, String date, Map<String, BigDecimal> rates) {
    }
}
