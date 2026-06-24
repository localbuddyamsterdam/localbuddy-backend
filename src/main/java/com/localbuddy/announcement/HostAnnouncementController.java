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
@RequestMapping("/api/host/announcements")
@Tag(name = "Host - Announcements", description = "Hosts broadcast announcements to their followers and/or guests")
@SecurityRequirement(name = "bearerAuth")
public class HostAnnouncementController {

    private final AnnouncementService announcementService;

    public HostAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Operation(summary = "Post an announcement",
            description = "Sends to MY_FOLLOWERS, MY_GUESTS, or BOTH. Delivered in-app + email (email respects "
                    + "the recipient's email preference). Approved hosts only.")
    @PostMapping
    public ResponseEntity<AnnouncementResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateAnnouncementRequest request
    ) {
        UUID hostUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.createHostAnnouncement(hostUserId, request));
    }

    @Operation(summary = "List my announcements")
    @GetMapping
    public ResponseEntity<List<AnnouncementResponse>> listMine(Authentication authentication) {
        UUID hostUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(announcementService.listMyHostAnnouncements(hostUserId));
    }
}
