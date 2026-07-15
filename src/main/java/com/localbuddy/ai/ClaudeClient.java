package com.localbuddy.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the Anthropic Messages API. Stays dormant (isConfigured == false)
 * until an API key is provided, so the application runs without AI configured.
 *
 * <p>Supports plain single-turn text completions ({@link #complete}) and multi-turn,
 * JSON-schema-constrained calls ({@link #completeStructured}) used by the trip planner,
 * chat assistant, and review summaries. Transient upstream failures (429/5xx/529 and
 * network errors) are retried with a short backoff, honouring Retry-After when present.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private static final long MAX_RETRY_DELAY_MS = 10_000L;

    private final RestClient restClient;
    private final RestClient longRunningRestClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int defaultMaxTokens;
    private final int maxRetries;
    private final int dailyCallCap;

    // Redis-independent cost backstop: model calls made since the (UTC) day started.
    private final java.util.concurrent.atomic.AtomicInteger dailyCallCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.time.LocalDate dailyCallDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

    public ClaudeClient(
            ObjectMapper objectMapper,
            @Value("${app.ai.anthropic.api-key:}") String apiKey,
            @Value("${app.ai.anthropic.model:claude-sonnet-5}") String model,
            @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${app.ai.anthropic.max-tokens:1024}") int maxTokens,
            @Value("${app.ai.anthropic.timeout-seconds:90}") int timeoutSeconds,
            @Value("${app.ai.anthropic.long-timeout-seconds:300}") int longTimeoutSeconds,
            @Value("${app.ai.anthropic.max-retries:2}") int maxRetries,
            @Value("${app.ai.anthropic.daily-call-cap:2000}") int dailyCallCap
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.defaultMaxTokens = maxTokens;
        this.maxRetries = Math.max(0, maxRetries);
        this.dailyCallCap = dailyCallCap;

        this.restClient = buildClient(baseUrl, Math.max(10, timeoutSeconds));
        this.longRunningRestClient = buildClient(baseUrl, Math.max(60, longTimeoutSeconds));
    }

    private RestClient buildClient(String baseUrl, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String getModel() {
        return model;
    }

    /** Send a single-turn prompt and return the concatenated text content. */
    public String complete(String systemPrompt, String userPrompt) {
        requireConfigured();

        Map<String, Object> body = baseBody(defaultMaxTokens);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));

        return extractText(sendWithRetries(body, false));
    }

    /**
     * Multi-turn call whose response is constrained to the given JSON schema via the API's
     * structured-output support, so the returned node is guaranteed to match the schema.
     * Throws {@link ServiceUnavailableException} when the provider is unconfigured, keeps
     * failing after retries, or the response was truncated before the JSON completed.
     */
    public AiStructuredResult completeStructured(
            String systemPrompt,
            List<AiMessage> messages,
            JsonNode outputSchema,
            AiCallOptions options
    ) {
        requireConfigured();

        Map<String, Object> body = baseBody(options.maxTokens());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }

        List<Map<String, String>> wireMessages = new ArrayList<>();
        for (AiMessage message : messages) {
            wireMessages.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("messages", wireMessages);

        // The request body is serialized by the RestClient's message converter, which
        // serializes a JsonNode as its bean getters (isArray/isObject/nodeType/…) rather than
        // as the JSON tree it represents — producing a garbage schema Anthropic rejects with
        // "Schema type is missing". Convert it to a plain object tree so it serializes as the
        // real JSON Schema regardless of which Jackson the converter uses.
        Object outputSchemaJson = objectMapper.convertValue(outputSchema, Object.class);
        Map<String, Object> outputConfig = new HashMap<>();
        outputConfig.put("format", Map.of("type", "json_schema", "schema", outputSchemaJson));
        if (options.effort() != null && !options.effort().isBlank()) {
            outputConfig.put("effort", options.effort());
        }
        body.put("output_config", outputConfig);

        if (options.disableThinking()) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        JsonNode response = sendWithRetries(body, options.longRunning());
        String stopReason = response.path("stop_reason").asText(null);
        if ("max_tokens".equals(stopReason)) {
            throw new ServiceUnavailableException(
                    "The AI response was cut off before completing; please try again with a smaller request");
        }

        String text = extractText(response);
        JsonNode json;
        try {
            json = objectMapper.readTree(text);
        } catch (Exception ex) {
            log.error("Anthropic structured response was not valid JSON (stop_reason={})", stopReason, ex);
            throw new ServiceUnavailableException("The AI service returned an unreadable response; please try again");
        }

        JsonNode usage = response.path("usage");
        return new AiStructuredResult(
                json,
                usage.path("input_tokens").isNumber() ? usage.path("input_tokens").asInt() : null,
                usage.path("output_tokens").isNumber() ? usage.path("output_tokens").asInt() : null,
                stopReason
        );
    }

    private Map<String, Object> baseBody(int maxTokens) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        return body;
    }

    private JsonNode sendWithRetries(Map<String, Object> body, boolean longRunning) {
        enforceDailyCallCap();
        RestClient client = longRunning ? longRunningRestClient : restClient;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // Read the body as a String and parse it with our (Jackson 2) ObjectMapper.
                // Spring Boot 4's RestClient message converter is Jackson 3 (tools.jackson), which
                // cannot construct a Jackson 2 com.fasterxml JsonNode — binding to JsonNode.class
                // throws InvalidDefinitionException (surfacing as a 500).
                String rawResponse = client.post()
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                if (rawResponse == null || rawResponse.isBlank()) {
                    throw new ServiceUnavailableException("The AI service returned an empty response");
                }
                try {
                    return objectMapper.readTree(rawResponse);
                } catch (Exception parseEx) {
                    log.error("Anthropic response was not valid JSON", parseEx);
                    throw new ServiceUnavailableException(
                            "The AI service returned an unreadable response; please try again");
                }
            } catch (RestClientResponseException ex) {
                if (!isRetryable(ex.getStatusCode().value()) || attempt == maxRetries) {
                    log.error("Anthropic request failed with status {} (attempt {}/{}): {}",
                            ex.getStatusCode().value(), attempt + 1, maxRetries + 1,
                            truncate(ex.getResponseBodyAsString()));
                    throw new ServiceUnavailableException("The AI service is temporarily unavailable; please try again");
                }
                sleepBeforeRetry(retryDelayMs(ex, attempt));
            } catch (ResourceAccessException ex) {
                // A READ timeout means the model already spent the tokens; blindly re-running
                // multiplies cost and latency for a request that will likely time out again.
                // Only connect-level failures are worth retrying.
                if (isReadTimeout(ex) || attempt == maxRetries) {
                    log.error("Anthropic request failed with a network error (readTimeout={}, attempt {}/{})",
                            isReadTimeout(ex), attempt + 1, maxRetries + 1, ex);
                    throw new ServiceUnavailableException("The AI service is temporarily unreachable; please try again");
                }
                sleepBeforeRetry((attempt + 1) * 1_000L);
            }
        }

        // Unreachable: the loop always returns or throws above.
        throw new ServiceUnavailableException("The AI service is temporarily unavailable; please try again");
    }

    private boolean isReadTimeout(ResourceAccessException ex) {
        return ex.getCause() instanceof java.net.SocketTimeoutException;
    }

    /**
     * Hard daily ceiling on model calls, independent of Redis rate limiting (which fails
     * open). Purely a runaway-cost backstop; 0 disables it.
     */
    private void enforceDailyCallCap() {
        if (dailyCallCap <= 0) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        if (!today.equals(dailyCallDay)) {
            synchronized (this) {
                if (!today.equals(dailyCallDay)) {
                    dailyCallDay = today;
                    dailyCallCount.set(0);
                }
            }
        }
        if (dailyCallCount.incrementAndGet() > dailyCallCap) {
            log.error("Daily AI call cap of {} reached — refusing further model calls until the UTC day rolls over",
                    dailyCallCap);
            throw new ServiceUnavailableException("AI features have reached today's usage limit; please try again later");
        }
    }

    private boolean isRetryable(int status) {
        return status == 429 || status == 529 || (status >= 500 && status < 600);
    }

    private long retryDelayMs(RestClientResponseException ex, int attempt) {
        String retryAfter = ex.getResponseHeaders() != null
                ? ex.getResponseHeaders().getFirst("retry-after")
                : null;
        if (retryAfter != null) {
            try {
                return Math.min(Long.parseLong(retryAfter.trim()) * 1_000L, MAX_RETRY_DELAY_MS);
            } catch (NumberFormatException ignored) {
                // fall through to the default backoff
            }
        }
        return Math.min((attempt + 1) * 1_000L, MAX_RETRY_DELAY_MS);
    }

    private void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("The AI request was interrupted");
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode content = response.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) + "…" : value;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ServiceUnavailableException(
                    "AI features are not available yet (Anthropic API key is not configured)");
        }
    }
}
