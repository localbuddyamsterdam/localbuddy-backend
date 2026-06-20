package com.localbuddy.waitlist;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/public/waitlist")
public class PublicWaitlistController {

    private final WaitlistService waitlistService;

    public PublicWaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping("/slots/{slotId}")
    public ResponseEntity<WaitlistResponse> joinWaitlistAsGuest(
            @PathVariable UUID slotId,
            @Valid @RequestBody JoinGuestWaitlistRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(waitlistService.joinAsGuest(slotId, request));
    }
}
