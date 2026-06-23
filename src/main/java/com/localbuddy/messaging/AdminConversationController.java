package com.localbuddy.messaging;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/conversations")
@Tag(name = "Admin - Messaging", description = "Admin monitoring, takeover, and side conversations")
@SecurityRequirement(name = "bearerAuth")
public class AdminConversationController {

    private final ConversationService conversationService;

    public AdminConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Operation(summary = "List all conversations",
            description = "Every conversation in the system, newest activity first (monitoring).")
    @GetMapping
    public ResponseEntity<List<ConversationResponse>> listAll() {
        return ResponseEntity.ok(conversationService.adminListConversations());
    }

    @Operation(summary = "Read any conversation's messages",
            description = "Admins can read any thread without being a member.")
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable UUID conversationId) {
        return ResponseEntity.ok(conversationService.adminGetMessages(conversationId));
    }

    @Operation(summary = "Reply to / take over a conversation",
            description = "Posts a message into any conversation. The admin joins as a participant; "
                    + "the message is labelled \"Admin (Name)\" and is visible to all participants.")
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<MessageResponse> reply(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.adminReply(adminUserId, conversationId, request));
    }

    @Operation(summary = "Start a private side conversation",
            description = "Opens a private thread between the admin and a single customer or host.")
    @PostMapping("/side")
    public ResponseEntity<ConversationResponse> startSideConversation(
            Authentication authentication,
            @Valid @RequestBody AdminStartSideConversationRequest request
    ) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.adminStartSideConversation(adminUserId, request));
    }
}
