package com.localbuddy.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Assistant", description = "Claude-powered listing copy, itinerary suggestions, and content moderation")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @Operation(summary = "AI availability", description = "Reports whether AI features are configured on this environment.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("configured", aiService.isConfigured()));
    }

    @Operation(summary = "Draft listing copy", description = "Generates short and detailed descriptions for an experience.")
    @PostMapping("/listing-assistant")
    public ResponseEntity<ListingAssistantResponse> listingAssistant(@Valid @RequestBody ListingAssistantRequest request) {
        return ResponseEntity.ok(aiService.generateListing(request));
    }

    @Operation(summary = "Suggest an itinerary")
    @PostMapping("/itinerary")
    public ResponseEntity<ItineraryResponse> itinerary(@Valid @RequestBody ItineraryRequest request) {
        return ResponseEntity.ok(aiService.suggestItinerary(request));
    }

    @Operation(summary = "Moderate content", description = "Classifies free text for policy violations.")
    @PostMapping("/moderation")
    public ResponseEntity<ModerationResponse> moderation(@Valid @RequestBody ModerationRequest request) {
        return ResponseEntity.ok(aiService.moderate(request));
    }
}
