package com.localbuddy.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;

    public AiService(ClaudeClient claudeClient, ObjectMapper objectMapper) {
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return claudeClient.isConfigured();
    }

    public ListingAssistantResponse generateListing(ListingAssistantRequest request) {
        String system = "You are a marketing copywriter for a marketplace of local experiences. "
                + "Write vivid but honest, inclusive copy. Respond ONLY with a JSON object with keys "
                + "\"shortDescription\" (max ~250 characters, one punchy sentence) and "
                + "\"detailedDescription\" (2-4 short paragraphs). Do not include any text outside the JSON.";

        StringBuilder user = new StringBuilder();
        user.append("Write listing copy for an experience.\n");
        user.append("Title: ").append(request.title()).append("\n");
        if (request.city() != null && !request.city().isBlank()) {
            user.append("City: ").append(request.city()).append("\n");
        }
        if (request.category() != null && !request.category().isBlank()) {
            user.append("Category: ").append(request.category()).append("\n");
        }
        if (request.highlights() != null && !request.highlights().isBlank()) {
            user.append("Highlights / notes from the host: ").append(request.highlights()).append("\n");
        }

        String raw = claudeClient.complete(system, user.toString());
        JsonNode json = tryParseJson(raw);
        if (json != null && json.has("detailedDescription")) {
            return new ListingAssistantResponse(
                    json.path("shortDescription").asText(null),
                    json.path("detailedDescription").asText(null)
            );
        }
        // Fallback: model did not return valid JSON; use raw text as the detailed copy.
        return new ListingAssistantResponse(null, raw);
    }

    public ItineraryResponse suggestItinerary(ItineraryRequest request) {
        StringBuilder user = new StringBuilder();
        user.append("Suggest a local day itinerary for a traveler in ").append(request.city()).append(".\n");
        if (request.interests() != null && !request.interests().isBlank()) {
            user.append("Interests: ").append(request.interests()).append("\n");
        }
        if (request.durationHours() != null) {
            user.append("Available time: about ").append(request.durationHours()).append(" hours.\n");
        }
        if (request.partySize() != null) {
            user.append("Party size: ").append(request.partySize()).append(".\n");
        }
        user.append("Give a friendly, practical itinerary with rough timings and a few local tips.");

        String system = "You are a knowledgeable, friendly local travel guide. Keep suggestions realistic and safe.";
        return new ItineraryResponse(claudeClient.complete(system, user.toString()));
    }

    public ModerationResponse moderate(ModerationRequest request) {
        String system = "You are a content-moderation classifier for a travel marketplace. Decide whether the user "
                + "text violates policy (hate, harassment, sexual content involving minors, violence, illegal activity, "
                + "spam, or sharing of personal contact details to evade the platform). Respond ONLY with a JSON object "
                + "with keys \"flagged\" (boolean) and \"reason\" (short string, empty if not flagged).";

        String raw = claudeClient.complete(system, "Classify the following text:\n\n" + request.text());
        JsonNode json = tryParseJson(raw);
        if (json != null && json.has("flagged")) {
            return new ModerationResponse(
                    json.path("flagged").asBoolean(false),
                    json.path("reason").asText("")
            );
        }
        // Fallback: if we cannot parse a verdict, do not block; surface the raw note.
        return new ModerationResponse(false, raw);
    }

    /** Best-effort JSON extraction; tolerates Markdown code fences around the JSON. */
    private JsonNode tryParseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(cleaned.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
