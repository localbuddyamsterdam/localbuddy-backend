package com.localbuddy.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.experience.ExperienceResponse;
import com.localbuddy.experience.ExperienceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public AI concierge: answers traveler questions about LocalBuddy and the destination,
 * grounded in the real experience catalog. Experience recommendations come back as slugs
 * that are validated against the catalog — anything the model invents is dropped, and the
 * server (not the model) builds the links.
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private static final String OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["reply", "suggestedExperienceSlugs", "escalateToSupport"],
              "properties": {
                "reply": {"type": "string"},
                "suggestedExperienceSlugs": {"type": "array", "items": {"type": "string"}},
                "escalateToSupport": {"type": "boolean"}
              }
            }
            """;

    private static final String PLATFORM_FACTS = """
            ABOUT LOCALBUDDY:
            - LocalBuddy is a marketplace where travelers book small-group experiences (tours, food, \
            culture, outdoors) hosted by vetted locals ("buddies").
            - Booking: open an experience page, pick a date and start time, then pay online (Stripe). \
            No account is required — guests can book with just name, email, and phone; a booking \
            reference is emailed for managing the booking later.
            - At checkout travelers can apply one promo or referral code (promos can stack) and a gift \
            card. Gift cards are bought and checked on the site.
            - Cancellations: travelers cancel from Account > Trips (or View/Manage booking for guests). \
            Refunds follow the cancellation policy shown at checkout; cancellations within 24 hours of \
            the start time cannot be done self-service — contact support instead.
            - If too few guests join a session, the host may cancel it in advance with a full refund.
            - Emergency contact details can be added at booking; there is a Trust & Safety centre on the \
            site for reporting concerns.
            - Useful pages: /search (all experiences), /trip-planner (AI trip planner), /help (help \
            centre), /contact (contact form), /booking/track (view or manage a booking), /gift-cards, \
            /become-host (for locals who want to host).
            """;

    private final ClaudeClient claudeClient;
    private final ExperienceService experienceService;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;
    private final int maxTokens;
    private final int historyLimit;
    private final int maxExperiencesInPrompt;

    public AiChatService(
            ClaudeClient claudeClient,
            ExperienceService experienceService,
            ObjectMapper objectMapper,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.ai.chat.max-tokens:1200}") int maxTokens,
            @Value("${app.ai.chat.history-limit:20}") int historyLimit,
            @Value("${app.ai.chat.max-experiences-in-prompt:30}") int maxExperiencesInPrompt
    ) {
        this.claudeClient = claudeClient;
        this.experienceService = experienceService;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        this.maxTokens = maxTokens;
        this.historyLimit = historyLimit;
        this.maxExperiencesInPrompt = maxExperiencesInPrompt;
    }

    public boolean isConfigured() {
        return claudeClient.isConfigured();
    }

    public ChatResponse chat(ChatRequest request) {
        List<AiMessage> conversation = normalizeHistory(request.messages());

        Map<String, ExperienceResponse> catalog = loadCatalog(request.citySlug());

        String systemPrompt = buildSystemPrompt(catalog);
        JsonNode schema = readSchema();

        AiStructuredResult result = claudeClient.completeStructured(
                systemPrompt, conversation, schema, AiCallOptions.lowLatency(maxTokens));

        JsonNode json = result.json();
        String reply = json.path("reply").asText("").trim();
        boolean escalate = json.path("escalateToSupport").asBoolean(false);

        List<ChatSuggestion> suggestions = new ArrayList<>();
        for (JsonNode slugNode : json.path("suggestedExperienceSlugs")) {
            if (suggestions.size() >= 4) {
                break;
            }
            ExperienceResponse experience = catalog.get(slugNode.asText("").trim());
            if (experience == null) {
                continue; // model invented a slug — drop it
            }
            suggestions.add(new ChatSuggestion(
                    experience.id(),
                    experience.slug(),
                    experience.title(),
                    frontendBaseUrl + "/experience/" + experience.slug(),
                    experience.priceAmount(),
                    experience.currency(),
                    experience.categoryName()
            ));
        }

        return new ChatResponse(
                reply,
                suggestions,
                escalate,
                escalate ? frontendBaseUrl + "/help" : null
        );
    }

    private List<AiMessage> normalizeHistory(List<ChatMessage> messages) {
        // Keep the newest turns, drop leading assistant turns (the API requires user-first),
        // and require the latest turn to be the user's question.
        List<ChatMessage> trimmed = messages.size() > historyLimit
                ? messages.subList(messages.size() - historyLimit, messages.size())
                : messages;

        int firstUser = 0;
        while (firstUser < trimmed.size() && !"user".equals(trimmed.get(firstUser).role())) {
            firstUser++;
        }
        List<ChatMessage> usable = trimmed.subList(firstUser, trimmed.size());

        if (usable.isEmpty() || !"user".equals(usable.get(usable.size() - 1).role())) {
            throw new BadRequestException("The last chat message must come from the user");
        }

        List<AiMessage> conversation = new ArrayList<>();
        for (ChatMessage message : usable) {
            conversation.add(new AiMessage(message.role(), message.content().trim()));
        }
        return conversation;
    }

    private Map<String, ExperienceResponse> loadCatalog(String citySlug) {
        Map<String, ExperienceResponse> catalog = new LinkedHashMap<>();
        try {
            String normalizedCity = citySlug != null && !citySlug.isBlank()
                    ? citySlug.trim().toLowerCase() : null;
            List<ExperienceResponse> experiences =
                    experienceService.getApprovedExperiences(normalizedCity, null, null, null);
            for (ExperienceResponse experience : experiences) {
                if (catalog.size() >= maxExperiencesInPrompt) {
                    break;
                }
                catalog.put(experience.slug(), experience);
            }
        } catch (Exception ex) {
            // Catalog grounding is best-effort: the assistant still answers platform questions.
            log.warn("AI chat catalog grounding skipped: {}", ex.getMessage());
        }
        return catalog;
    }

    private String buildSystemPrompt(Map<String, ExperienceResponse> catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are "Buddy", LocalBuddy's friendly AI travel concierge on the public website. \
                You help visitors discover experiences, plan their visit, and answer questions about \
                how LocalBuddy works.

                RULES:
                - Be warm, concise, and practical. Keep replies under 150 words.
                - Recommend ONLY experiences from the catalog below, by putting their exact slugs in \
                suggestedExperienceSlugs (max 4). Never invent experiences, prices, availability, or dates. \
                Don't paste raw links in the reply text — the site renders your suggestions as cards.
                - You may answer general travel questions about the destination (neighborhoods, food, \
                etiquette, transport) from common knowledge, clearly as suggestions.
                - You cannot see accounts, bookings, or payments. For anything account-specific (refund \
                status, changing a specific booking, payment problems, complaints, safety issues), set \
                escalateToSupport to true and point the visitor to the help centre.
                - Never promise policy exceptions, discounts, or refunds. State policies only as described.
                - If asked something unrelated to travel or LocalBuddy, politely steer back.

                """);
        sb.append(PLATFORM_FACTS);

        sb.append("\nCATALOG (the only experiences you may recommend):\n");
        if (catalog.isEmpty()) {
            sb.append("(no experiences available right now)\n");
        }
        for (ExperienceResponse experience : catalog.values()) {
            sb.append("- slug: ").append(experience.slug())
                    .append(" | title: ").append(experience.title())
                    .append(" | city: ").append(experience.cityName());
            if (experience.categoryName() != null) {
                sb.append(" | category: ").append(experience.categoryName());
            }
            if (experience.priceAmount() != null) {
                sb.append(" | from ").append(experience.priceAmount()).append(" ").append(experience.currency())
                        .append(" per guest");
            }
            if (experience.durationMinutes() != null) {
                sb.append(" | ").append(experience.durationMinutes()).append(" min");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private JsonNode readSchema() {
        try {
            return objectMapper.readTree(OUTPUT_SCHEMA_JSON);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid chat output schema", ex);
        }
    }
}
