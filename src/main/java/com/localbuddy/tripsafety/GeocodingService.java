package com.localbuddy.tripsafety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Best-effort reverse geocoding (lat/lng → street address) to make an SOS alert readable.
 * Env-gated: a blank api-key leaves it dormant ({@link #isConfigured()} == false), so the
 * app boots and SOS works with no provider — the alert just shows raw coordinates + map
 * links. Google Geocoding-compatible: {@code GET {url}?latlng={lat},{lng}&key={key}}.
 *
 * <p>Never throws to callers and uses short timeouts — an SOS must never be delayed or
 * failed because geocoding is slow or down.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String url;
    private final RestClient client;

    public GeocodingService(ObjectMapper objectMapper,
                            @Value("${app.geocoding.api-key:}") String apiKey,
                            @Value("${app.geocoding.url:https://maps.googleapis.com/maps/api/geocode/json}") String url,
                            @Value("${app.geocoding.timeout-seconds:3}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.url = url;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Reverse-geocode; returns empty on any failure, missing coords, or when unconfigured. */
    public Optional<String> reverseGeocode(Double latitude, Double longitude) {
        if (!isConfigured() || latitude == null || longitude == null) {
            return Optional.empty();
        }
        try {
            // Spring Boot 4 RestClient uses Jackson 3; bind to String and parse with the
            // app's Jackson-2 ObjectMapper (see ClaudeClient — binding JsonNode directly 500s).
            String raw = client.get()
                    .uri(url + "?latlng={ll}&key={key}", latitude + "," + longitude, apiKey)
                    .retrieve()
                    .body(String.class);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            JsonNode results = objectMapper.readTree(raw).path("results");
            if (results.isArray() && !results.isEmpty()) {
                String formatted = results.get(0).path("formatted_address").asText(null);
                if (formatted != null && !formatted.isBlank()) {
                    return Optional.of(formatted.length() > 500 ? formatted.substring(0, 500) : formatted);
                }
            }
        } catch (Exception e) {
            log.warn("Reverse geocoding failed (non-fatal): {}", e.getMessage());
        }
        return Optional.empty();
    }
}
