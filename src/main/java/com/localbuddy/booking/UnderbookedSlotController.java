package com.localbuddy.booking;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/availability")
@Tag(name = "Underbooked slots", description = "Host cancellation of under-booked experience slots")
@SecurityRequirement(name = "bearerAuth")
public class UnderbookedSlotController {

    private final UnderbookedSlotService underbookedSlotService;

    public UnderbookedSlotController(UnderbookedSlotService underbookedSlotService) {
        this.underbookedSlotService = underbookedSlotService;
    }

    @PostMapping("/{slotId}/cancel-underbooked")
    public ResponseEntity<UnderbookedSlotCancellationResponse> cancelUnderbookedSlot(
            Authentication authentication,
            @PathVariable UUID slotId,
            @RequestBody(required = false) CancelBookingRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(underbookedSlotService.cancelUnderbookedSlot(userId, slotId, reason));
    }
}
