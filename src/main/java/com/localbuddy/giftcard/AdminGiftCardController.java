package com.localbuddy.giftcard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/gift-cards")
@Tag(name = "Admin - Gift Cards", description = "Admin management of gift cards")
public class AdminGiftCardController {

    private final GiftCardService giftCardService;

    public AdminGiftCardController(GiftCardService giftCardService) {
        this.giftCardService = giftCardService;
    }

    @Operation(summary = "List all gift cards")
    @GetMapping
    public ResponseEntity<List<GiftCardResponse>> listAll() {
        return ResponseEntity.ok(giftCardService.listAll());
    }

    @Operation(summary = "Cancel a gift card")
    @PostMapping("/{giftCardId}/cancel")
    public ResponseEntity<GiftCardResponse> cancel(@PathVariable UUID giftCardId) {
        return ResponseEntity.ok(giftCardService.cancel(giftCardId));
    }
}
