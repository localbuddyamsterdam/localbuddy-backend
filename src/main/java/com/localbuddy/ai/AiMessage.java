package com.localbuddy.ai;

/**
 * One turn of an Anthropic Messages API conversation. Role is "user" or "assistant"
 * (the system prompt travels separately on the request).
 */
public record AiMessage(String role, String content) {

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }
}
