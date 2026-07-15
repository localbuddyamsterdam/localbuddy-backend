package com.localbuddy.ai;

/**
 * Per-call tuning for {@link ClaudeClient}. {@code effort} maps to the API's
 * output_config.effort (null = provider default); {@code disableThinking} turns the
 * model's adaptive thinking off (predictable token budget / latency);
 * {@code longRunning} switches to the extended read timeout for long generations.
 */
public record AiCallOptions(int maxTokens, String effort, boolean disableThinking, boolean longRunning) {

    public static AiCallOptions ofMaxTokens(int maxTokens) {
        return new AiCallOptions(maxTokens, null, false, false);
    }

    /** Snappy interactive calls (chat, short summaries): low effort, no thinking. */
    public static AiCallOptions lowLatency(int maxTokens) {
        return new AiCallOptions(maxTokens, "low", true, false);
    }

    /**
     * Long-form single generations (trip plans): thinking off so the whole token budget goes
     * to the answer (adaptive thinking would share max_tokens and could truncate the JSON),
     * and the extended read timeout so a long generation isn't aborted and re-billed.
     */
    public static AiCallOptions longGeneration(int maxTokens) {
        return new AiCallOptions(maxTokens, null, true, true);
    }
}
