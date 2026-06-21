package com.localbuddy.whatsapp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/whatsapp")
@Tag(name = "WhatsApp", description = "Click-to-chat links and booking contact links")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;
    private final WhatsAppBookingService whatsAppBookingService;

    public WhatsAppController(WhatsAppService whatsAppService,
                             WhatsAppBookingService whatsAppBookingService) {
        this.whatsAppService = whatsAppService;
        this.whatsAppBookingService = whatsAppBookingService;
    }

    @Operation(summary = "Build a click-to-chat link",
            description = "Returns a wa.me link for the given phone with an optional prefilled message.")
    @GetMapping("/click-to-chat")
    public ResponseEntity<ClickToChatResponse> clickToChat(
            @RequestParam String phone,
            @RequestParam(required = false) String message
    ) {
        return ResponseEntity.ok(new ClickToChatResponse(whatsAppService.buildClickToChatLink(phone, message)));
    }

    @Operation(summary = "Contact the other party on a booking",
            description = "Returns a click-to-chat link to message the booking's host (for travelers) or traveler (for hosts).")
    @GetMapping("/bookings/{bookingId}/contact")
    public ResponseEntity<BookingContactLinkResponse> bookingContact(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(whatsAppBookingService.contactLink(userId, bookingId));
    }
}
