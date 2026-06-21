package com.localbuddy.whatsapp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/whatsapp")
@Tag(name = "Admin - WhatsApp", description = "Send WhatsApp messages via the Business API (Meta Cloud)")
public class AdminWhatsAppController {

    private final WhatsAppService whatsAppService;

    public AdminWhatsAppController(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    @Operation(summary = "Business API status", description = "Whether outbound WhatsApp sending is configured.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("configured", whatsAppService.isConfigured()));
    }

    @Operation(summary = "Send a WhatsApp message", description = "Sends a text message via the Meta Cloud API.")
    @PostMapping("/send")
    public ResponseEntity<WhatsAppSendResult> send(@Valid @RequestBody SendWhatsAppRequest request) {
        return ResponseEntity.ok(whatsAppService.sendMessage(request.toPhone(), request.message()));
    }
}
