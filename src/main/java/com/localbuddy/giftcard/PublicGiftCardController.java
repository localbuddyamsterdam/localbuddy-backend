package com.localbuddy.giftcard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/gift-cards")
@Tag(name = "Public Gift Cards", description = "Public gift card balance lookup")
public class PublicGiftCardController {

    private final GiftCardService giftCardService;

    public PublicGiftCardController(GiftCardService giftCardService) {
        this.giftCardService = giftCardService;
    }

    @Operation(summary = "Check a gift card balance by code")
    @GetMapping("/{code}/balance")
    public ResponseEntity<GiftCardBalanceResponse> checkBalance(@PathVariable String code) {
        return ResponseEntity.ok(giftCardService.checkBalance(code));
    }
}
