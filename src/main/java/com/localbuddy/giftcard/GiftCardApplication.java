package com.localbuddy.giftcard;

import java.math.BigDecimal;
import java.util.UUID;

/** The result of reserving gift-card balance for a booking checkout: which card, and how much it covered. */
public record GiftCardApplication(UUID giftCardId, BigDecimal amount) {
}
