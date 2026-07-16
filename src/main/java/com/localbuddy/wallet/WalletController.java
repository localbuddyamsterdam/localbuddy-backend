package com.localbuddy.wallet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings/{bookingId}/wallet")
@Tag(name = "Booking Wallet", description = "Add a booking to Apple Wallet or Google Wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Operation(summary = "Wallet availability + links",
            description = "Returns which wallets are configured plus the Google save link (participant-only).")
    @GetMapping
    public ResponseEntity<WalletLinksResponse> links(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(walletService.links(userId, bookingId));
    }

    @Operation(summary = "Google Wallet save link",
            description = "Returns a 'Save to Google Wallet' URL for the booking.")
    @GetMapping("/google")
    public ResponseEntity<Map<String, String>> google(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(Map.of("saveUrl", walletService.googleSaveUrl(userId, bookingId)));
    }

    @Operation(summary = "Apple Wallet pass",
            description = "Downloads the signed .pkpass file for the booking.")
    @GetMapping("/apple.pkpass")
    public ResponseEntity<byte[]> apple(
            Authentication authentication,
            @PathVariable UUID bookingId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        WalletService.PassFile pass = walletService.applePass(userId, bookingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pass.filename() + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.apple.pkpass"))
                .body(pass.bytes());
    }
}
