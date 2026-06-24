package com.localbuddy.announcement;

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
@RequestMapping("/api/admin/announcements")
@Tag(name = "Admin - Announcements", description = "Platform announcements to all hosts + announcement log")
@SecurityRequirement(name = "bearerAuth")
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    public AdminAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Operation(summary = "Send a platform announcement to all hosts")
    @PostMapping("/platform")
    public ResponseEntity<AnnouncementResponse> platform(
            Authentication authentication,
            @Valid @RequestBody PlatformAnnouncementRequest request
    ) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.createPlatformAnnouncement(adminUserId, request));
    }

    @Operation(summary = "List all announcements")
    @GetMapping
    public ResponseEntity<List<AnnouncementResponse>> listAll() {
        return ResponseEntity.ok(announcementService.listAll());
    }
}
