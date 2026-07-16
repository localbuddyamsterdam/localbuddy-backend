package com.localbuddy.attraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiqets distributor API client — the aggregator that sells tickets for museums and
 * attractions (Rijksmuseum, Eiffel Tower summit, Van Gogh, ...). Env-gated like every other
 * integration: blank {@code TIQETS_API_KEY} means the whole attractions feature is off and
 * the app boots without it.
 *
 * <p><b>Two revenue modes, one toggle.</b> With just a partner id, {@code ticketUrl} carries
 * the affiliate tag and travelers buy on Tiqets (commission per sale, zero booking liability).
 * With a full distributor account and {@code app.attractions.booking-enabled=true}, orders are
 * placed through this client and travelers never leave LocalBuddy.
 *
 * <p><b>Field-name caveat:</b> response parsing is deliberately defensive ({@code JsonNode}
 * with fallbacks) because the exact distributor payload shapes can only be confirmed against
 * the partner sandbox once the account exists. The normalization points are all in this one
 * class — nothing outside it knows Tiqets field names.
 */
@Service
public class TiqetsClient {

    private static final Logger log = LoggerFactory.getLogger(TiqetsClient.class);

    private final String apiKey;
    private final String baseUrl;
    private final String partnerId;
    private final Map<String, String> cityIdBySlug;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TiqetsClient(
            @Value("${app.attractions.tiqets.api-key:}") String apiKey,
            @Value("${app.attractions.tiqets.base-url:https://api.tiqets.com/v2}") String baseUrl,
            @Value("${app.attractions.tiqets.partner-id:}") String partnerId,
            @Value("${app.attractions.tiqets.city-map:}") String cityMapCsv,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.partnerId = partnerId;
        this.cityIdBySlug = parseCityMap(cityMapCsv);
        this.objectMapper = objectMapper;
        // Fail fast on an unresponsive provider instead of hanging request threads.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Products in a city, normalized. Uses the configured Tiqets city id when the slug is
     * mapped ({@code app.attractions.tiqets.city-map}), otherwise falls back to a free-text
     * search on the city name.
     */
    public List<AttractionProduct> searchProducts(String citySlug, String cityName, String query, String language) {
        requireConfigured();
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + "/products")
                .queryParam("page_size", 24)
                .queryParam("lang", normalizeLanguage(language));
        String cityId = cityIdBySlug.get(citySlug == null ? "" : citySlug.toLowerCase(Locale.ROOT));
        if (cityId != null) {
            uri.queryParam("city_id", cityId);
        } else if (cityName != null && !cityName.isBlank()) {
            uri.queryParam("q", cityName.trim());
        }
        if (query != null && !query.isBlank()) {
            uri.queryParam("q", query.trim());
        }

        JsonNode body = get(uri.build().toUriString());
        JsonNode products = firstArray(body, "products", "results", "data");
        List<AttractionProduct> result = new ArrayList<>();
        for (JsonNode p : products) {
            AttractionProduct product = toProduct(p);
            if (product != null) {
                result.add(product);
            }
        }
        return result;
    }

    /** Availability (with entry timeslots when the product uses them) for a date range. */
    public List<AttractionAvailability> getAvailability(String productId, LocalDate from, LocalDate to) {
        requireConfigured();
        String url = UriComponentsBuilder
                .fromUriString(baseUrl + "/products/" + productId + "/availability")
                .queryParam("start_date", from)
                .queryParam("end_date", to)
                .build().toUriString();

        JsonNode body = get(url);
        JsonNode days = firstArray(body, "availability", "dates", "days", "data");
        List<AttractionAvailability> result = new ArrayList<>();
        for (JsonNode day : days) {
            LocalDate date = parseDate(text(day, "date", "day"));
            if (date == null) {
                continue;
            }
            List<AttractionAvailability.Timeslot> slots = new ArrayList<>();
            for (JsonNode slot : firstArray(day, "timeslots", "time_slots", "slots")) {
                String id = text(slot, "id", "timeslot_id");
                String time = text(slot, "start_time", "time", "timeslot");
                if (id != null || time != null) {
                    slots.add(new AttractionAvailability.Timeslot(
                            id != null ? id : time, time, boolValue(slot, true, "available", "is_available")));
                }
            }
            result.add(new AttractionAvailability(date, boolValue(day, true, "available", "is_available"), slots));
        }
        return result;
    }

    /** Result of a distributor order: the provider's order id plus where the tickets live. */
    public record TiqetsOrderResult(String orderId, String status, String ticketUrl) {
    }

    /**
     * Places a distributor order (tickets issued to the traveler's email; billing per the
     * distributor agreement). Only called when {@code app.attractions.booking-enabled} is on.
     */
    public TiqetsOrderResult createOrder(String productId, LocalDate date, String timeslotId, int quantity,
                                         String customerName, String customerEmail, String customerPhone) {
        requireConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", productId);
        payload.put("date", date.toString());
        if (timeslotId != null && !timeslotId.isBlank()) {
            payload.put("timeslot_id", timeslotId);
        }
        // Simplest variant model: N standard adult tickets. Ticket-type variants (child,
        // student, ...) are a follow-up once the sandbox confirms the variant payload shape.
        payload.put("ticket_count", quantity);
        payload.put("customer", Map.of(
                "name", customerName,
                "email", customerEmail,
                "phone", customerPhone == null ? "" : customerPhone));

        try {
            String response = restClient.post()
                    .uri(baseUrl + "/orders")
                    .header("Authorization", "Token " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode body = objectMapper.readTree(response == null ? "{}" : response);
            JsonNode order = body.has("order") ? body.get("order") : body;
            String orderId = text(order, "id", "order_id", "reference");
            if (orderId == null) {
                log.error("Tiqets order response had no order id: {}", truncate(response));
                throw new ServiceUnavailableException("The ticket provider returned an unexpected response");
            }
            return new TiqetsOrderResult(
                    orderId,
                    text(order, "status", "state"),
                    text(order, "ticket_url", "tickets_url", "download_url", "pdf_url"));
        } catch (RestClientResponseException ex) {
            log.error("Tiqets order failed: HTTP {} {}", ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString()));
            if (ex.getStatusCode().is4xxClientError()) {
                throw new BadRequestException("The ticket provider rejected this order — the date or "
                        + "timeslot may no longer be available");
            }
            throw new ServiceUnavailableException("The ticket provider is currently unavailable");
        } catch (ServiceUnavailableException | BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Tiqets order failed", ex);
            throw new ServiceUnavailableException("The ticket provider is currently unavailable");
        }
    }

    // ------------------------------------------------------------------
    // Normalization
    // ------------------------------------------------------------------

    private AttractionProduct toProduct(JsonNode p) {
        String id = text(p, "id", "product_id");
        String title = text(p, "title", "name");
        if (id == null || title == null) {
            return null;
        }
        String checkoutUrl = text(p, "product_checkout_url", "checkout_url", "product_url", "url");
        return new AttractionProduct(
                id,
                title,
                text(p, "tagline", "summary", "description_short"),
                text(p.path("city"), "name") != null ? text(p.path("city"), "name") : text(p, "city_name"),
                decimal(p, "price", "starting_price", "price_from"),
                textOrDefault(p, "EUR", "currency"),
                imageUrl(p),
                decimal(p, "ratings", "rating"),
                text(p, "duration", "visit_duration"),
                boolValue(p, false, "requires_timeslot", "has_timeslots", "timeslot_required"),
                withPartnerTag(checkoutUrl));
    }

    /** Affiliate attribution: every link-out carries our partner id so the sale is credited. */
    private String withPartnerTag(String url) {
        if (url == null || partnerId == null || partnerId.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "partner=" + partnerId;
    }

    private String imageUrl(JsonNode p) {
        JsonNode images = firstArray(p, "images");
        if (images.size() > 0) {
            JsonNode first = images.get(0);
            String url = text(first, "large", "medium", "url", "src");
            if (url != null) {
                return url;
            }
        }
        return text(p, "image_url", "image");
    }

    // ------------------------------------------------------------------
    // HTTP + JSON helpers
    // ------------------------------------------------------------------

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BadRequestException("Attraction tickets are not configured");
        }
    }

    private JsonNode get(String url) {
        try {
            String response = restClient.get()
                    .uri(url)
                    .header("Authorization", "Token " + apiKey)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (RestClientResponseException ex) {
            log.error("Tiqets GET {} failed: HTTP {} {}", url, ex.getStatusCode().value(),
                    truncate(ex.getResponseBodyAsString()));
            throw new ServiceUnavailableException("The ticket provider is currently unavailable");
        } catch (ServiceUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Tiqets GET {} failed", url, ex);
            throw new ServiceUnavailableException("The ticket provider is currently unavailable");
        }
    }

    /** "amsterdam:75061,paris:66746" → {amsterdam=75061, paris=66746}; malformed pairs skipped. */
    private static Map<String, String> parseCityMap(String csv) {
        Map<String, String> map = new HashMap<>();
        if (csv == null || csv.isBlank()) {
            return map;
        }
        for (String pair : csv.split(",")) {
            String[] parts = pair.split(":");
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                map.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
            }
        }
        return map;
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank() ? "en" : language.trim().toLowerCase(Locale.ROOT);
    }

    private static JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) {
                return value;
            }
        }
        return objectMapperEmptyArray;
    }

    private static final JsonNode objectMapperEmptyArray = new ObjectMapper().createArrayNode();

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String textOrDefault(JsonNode node, String fallback, String... fields) {
        String value = text(node, fields);
        return value != null ? value : fallback;
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual()) {
                try {
                    return new BigDecimal(value.asText());
                } catch (NumberFormatException ignored) {
                    // fall through to the next candidate field
                }
            }
        }
        return null;
    }

    private static boolean boolValue(JsonNode node, boolean fallback, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return fallback;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) + "…" : value;
    }
}
