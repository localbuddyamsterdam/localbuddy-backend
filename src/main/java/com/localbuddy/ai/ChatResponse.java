package com.localbuddy.ai;

import java.util.List;

/**
 * Assistant reply plus any real experiences it recommended (validated against the catalog —
 * hallucinated ones are dropped). {@code helpUrl} is set when the question needs a human.
 */
public record ChatResponse(
        String reply,
        List<ChatSuggestion> suggestions,
        boolean escalateToSupport,
        String helpUrl
) {
}
