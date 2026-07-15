package com.localbuddy.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of a schema-constrained model call: the parsed JSON plus usage metadata
 * (kept for cost monitoring and stored alongside generated content where useful).
 */
public record AiStructuredResult(
        JsonNode json,
        Integer inputTokens,
        Integer outputTokens,
        String stopReason
) {
}
