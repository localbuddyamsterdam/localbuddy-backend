package com.localbuddy.ai;

import java.math.BigDecimal;
import java.util.UUID;

/** A real experience the assistant recommended, with a direct link to its page. */
public record ChatSuggestion(
        UUID experienceId,
        String slug,
        String title,
        String url,
        BigDecimal priceAmount,
        String currency,
        String categoryName
) {
}
