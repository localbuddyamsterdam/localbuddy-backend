package com.localbuddy.waitlist;

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
@RequestMapping("/api/waitlist")
@Tag(name = "Waitlist", description = "Join and manage waitlists for full availability slots")
@SecurityRequirement(name = "bearerAuth")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping("/slots/{slotId}")
    public ResponseEntity<WaitlistResponse> joinWaitlist(
            Authentication authentication,
            @PathVariable UUID slotId,
            @Valid @RequestBody JoinWaitlistRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(waitlistService.joinAsUser(userId, slotId, request));
    }

    @GetMapping("/me")
    public ResponseEntity<List<WaitlistResponse>> getMyWaitlist(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(waitlistService.getMyWaitlist(userId));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> leaveWaitlist(
            Authentication authentication,
            @PathVariable UUID entryId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        waitlistService.leaveAsUser(userId, entryId);
        return ResponseEntity.noContent().build();
    }
}
