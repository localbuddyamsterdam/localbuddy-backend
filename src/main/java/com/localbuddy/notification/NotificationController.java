package com.localbuddy.notification;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notification feed for the authenticated user")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/me")
    public ResponseEntity<List<NotificationResponse>> getMyNotifications(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(notificationService.getMyNotifications(userId));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<NotificationResponse> markRead(
            Authentication authentication,
            @PathVariable UUID notificationId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(notificationService.markRead(userId, notificationId));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        notificationService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
