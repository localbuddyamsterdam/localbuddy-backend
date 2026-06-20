package com.localbuddy.ai;

public record ModerationResponse(
        boolean flagged,
        String reason
) {
}
