package com.localbuddy.announcement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/follows")
@Tag(name = "Follows", description = "Travelers follow hosts to receive their announcements")
@SecurityRequirement(name = "bearerAuth")
public class FollowController {

    private final AnnouncementService announcementService;

    public FollowController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Operation(summary = "Follow a host")
    @PostMapping("/{localProfileId}")
    public ResponseEntity<Void> follow(Authentication authentication, @PathVariable UUID localProfileId) {
        UUID userId = UUID.fromString(authentication.getName());
        announcementService.followHost(userId, localProfileId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unfollow a host")
    @DeleteMapping("/{localProfileId}")
    public ResponseEntity<Void> unfollow(Authentication authentication, @PathVariable UUID localProfileId) {
        UUID userId = UUID.fromString(authentication.getName());
        announcementService.unfollowHost(userId, localProfileId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List hosts I follow")
    @GetMapping("/me")
    public ResponseEntity<List<FollowedHostResponse>> myFollows(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(announcementService.listMyFollows(userId));
    }
}
