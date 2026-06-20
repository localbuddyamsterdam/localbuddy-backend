package com.localbuddy.messaging;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Messaging", description = "Authenticated in-app messaging between travelers and hosts")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Operation(summary = "Start or reuse a conversation",
            description = "Opens (or returns the existing) conversation between the current traveler and the host of an experience. Authenticated user.")
    @PostMapping
    public ResponseEntity<ConversationResponse> startConversation(
            Authentication authentication,
            @Valid @RequestBody StartConversationRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.startConversation(userId, request));
    }

    @Operation(summary = "List my conversations",
            description = "Returns the current user's conversations (as traveler or host), newest activity first, with unread counts.")
    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getMyConversations(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(conversationService.getMyConversations(userId));
    }

    @Operation(summary = "List messages in a conversation",
            description = "Returns the messages of a conversation the current user is a participant in. Authenticated user.")
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(
            Authentication authentication,
            @PathVariable UUID conversationId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(conversationService.getMessages(userId, conversationId));
    }

    @Operation(summary = "Send a message",
            description = "Posts a message to a conversation the current user is a participant in. Authenticated user.")
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.sendMessage(userId, conversationId, request));
    }

    @Operation(summary = "Mark a conversation read",
            description = "Marks all messages addressed to the current user in the conversation as read. Authenticated user.")
    @PostMapping("/{conversationId}/read")
    public ResponseEntity<Void> markRead(
            Authentication authentication,
            @PathVariable UUID conversationId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        conversationService.markRead(userId, conversationId);
        return ResponseEntity.noContent().build();
    }
}
