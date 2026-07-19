package com.localbuddy.whatsapp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    @Operation(summary = "Send a WhatsApp message", description = "Sends a text message via the Meta Cloud API. "
            + "Free-form text only delivers inside an open 24h customer-service session.")
    @PostMapping("/send")
    public ResponseEntity<WhatsAppSendResult> send(@Valid @RequestBody SendWhatsAppRequest request) {
        return ResponseEntity.ok(whatsAppService.sendMessage(request.toPhone(), request.message()));
    }

    @Operation(summary = "Send a template test message",
            description = "Sends a Meta-approved template (deliverable business-initiated, i.e. outside any open "
                    + "session) so the end-to-end path — credentials, template name, language, placeholders — can "
                    + "be verified right after Meta setup. Params fill the template's {{1}}..{{n}} body "
                    + "placeholders in order.")
    @PostMapping("/template-test")
    public ResponseEntity<WhatsAppSendResult> templateTest(@Valid @RequestBody SendTemplateTestRequest request) {
        return ResponseEntity.ok(whatsAppService.sendTemplate(
                request.toPhone(), request.templateName(), request.params() == null ? List.of() : request.params(),
                request.buttonParams()));
    }

    public record SendTemplateTestRequest(
            @NotBlank(message = "Recipient phone is required")
            String toPhone,
            @NotBlank(message = "Template name is required")
            String templateName,
            List<String> params,
            /** Dynamic suffixes for the template's URL buttons, in button-index order, if it has any. */
            List<String> buttonParams
    ) {
    }
}
