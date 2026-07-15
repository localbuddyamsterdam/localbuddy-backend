package com.localbuddy.ai;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public/ai")
@Tag(name = "AI Assistant (public)", description = "Catalog-grounded AI travel concierge for visitors and guests")
public class PublicAiChatController {

    private final AiChatService aiChatService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicAiChatController(
            AiChatService aiChatService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.aiChatService = aiChatService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "AI availability", description = "Whether AI features are configured on this environment.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("configured", aiChatService.isConfigured()));
    }

    @Operation(summary = "Chat with the AI concierge",
            description = "Stateless chat: send the whole conversation each turn. Replies are grounded in the "
                    + "real experience catalog; recommended experiences come back as structured suggestions with links.")
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ChatRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        // Tighter than the generic public limit — each call is a paid model generation.
        rateLimitService.checkPublicApiLimit("ai-chat:" + clientIp, 10, 60);
        rateLimitService.checkPublicApiLimit("ai-chat-daily:" + clientIp, 300, 86400);
        return ResponseEntity.ok(aiChatService.chat(request));
    }
}
