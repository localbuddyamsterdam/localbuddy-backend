package com.localbuddy.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/account/gdpr")
@Tag(name = "Account - GDPR", description = "Self-service data export and account deletion requests")
public class AccountGdprController {

    private final GdprService gdprService;

    public AccountGdprController(GdprService gdprService) {
        this.gdprService = gdprService;
    }

    @Operation(summary = "Export my data", description = "Returns a portable export of the authenticated user's personal data.")
    @GetMapping("/export")
    public ResponseEntity<GdprExportResponse> exportMyData(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(gdprService.exportMyData(userId));
    }

    @Operation(summary = "Request account deletion",
            description = "Submits a request to delete/anonymize the authenticated user's account.")
    @PostMapping("/delete-requests")
    public ResponseEntity<DataDeletionRequestResponse> requestDeletion(
            Authentication authentication,
            @Valid @RequestBody(required = false) CreateDataDeletionRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(gdprService.requestDeletion(userId, request));
    }

    @Operation(summary = "List my deletion requests")
    @GetMapping("/delete-requests")
    public ResponseEntity<List<DataDeletionRequestResponse>> getMyDeletionRequests(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(gdprService.getMyDeletionRequests(userId));
    }
}
