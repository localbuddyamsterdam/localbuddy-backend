package com.localbuddy.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/gdpr")
@Tag(name = "Admin - GDPR", description = "Admin processing of data deletion requests")
public class AdminGdprController {

    private final GdprService gdprService;

    public AdminGdprController(GdprService gdprService) {
        this.gdprService = gdprService;
    }

    @Operation(summary = "List deletion requests",
            description = "Lists deletion requests. Pass pendingOnly=true to see only requests awaiting action.")
    @GetMapping("/delete-requests")
    public ResponseEntity<List<DataDeletionRequestResponse>> listDeletionRequests(
            @RequestParam(defaultValue = "false") boolean pendingOnly
    ) {
        return ResponseEntity.ok(gdprService.listDeletionRequests(pendingOnly));
    }

    @Operation(summary = "Process (approve) a deletion request",
            description = "Anonymizes the requesting user's account and marks the request processed.")
    @PostMapping("/delete-requests/{requestId}/process")
    public ResponseEntity<DataDeletionRequestResponse> processDeletionRequest(
            @PathVariable UUID requestId,
            @RequestBody(required = false) ProcessDataDeletionRequest body
    ) {
        return ResponseEntity.ok(gdprService.processDeletionRequest(requestId, body));
    }

    @Operation(summary = "Reject a deletion request")
    @PostMapping("/delete-requests/{requestId}/reject")
    public ResponseEntity<DataDeletionRequestResponse> rejectDeletionRequest(
            @PathVariable UUID requestId,
            @RequestBody(required = false) ProcessDataDeletionRequest body
    ) {
        return ResponseEntity.ok(gdprService.rejectDeletionRequest(requestId, body));
    }
}
