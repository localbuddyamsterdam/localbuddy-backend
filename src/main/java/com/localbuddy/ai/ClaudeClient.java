package com.localbuddy.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.localbuddy.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the Anthropic Messages API. Stays dormant (isConfigured == false)
 * until an API key is provided, so the application runs without AI configured.
 */
@Component
public class ClaudeClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public ClaudeClient(
            @Value("${app.ai.anthropic.api-key:}") String apiKey,
            @Value("${app.ai.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${app.ai.anthropic.max-tokens:1024}") int maxTokens
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Send a single-turn prompt and return the concatenated text content. */
    public String complete(String systemPrompt, String userPrompt) {
        requireConfigured();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));

        try {
            JsonNode response = restClient.post()
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return extractText(response);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("AI request failed: " + ex.getMessage());
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

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BadRequestException("AI features are not available yet (Anthropic API key is not configured)");
        }
    }
}
