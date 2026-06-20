package com.localbuddy.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications/preferences")
@Tag(name = "Notification Preferences", description = "Per-user notification channel and reminder preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Operation(summary = "Get my notification preferences")
    @GetMapping
    public ResponseEntity<NotificationPreferenceResponse> getMyPreferences(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(preferenceService.getMyPreferences(userId));
    }

    @Operation(summary = "Update my notification preferences")
    @PutMapping
    public ResponseEntity<NotificationPreferenceResponse> updateMyPreferences(
            Authentication authentication,
            @Valid @RequestBody UpdateNotificationPreferenceRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(preferenceService.updateMyPreferences(userId, request));
    }
}
