package com.localbuddy.common.exception;

/** Thrown when a caller exceeds a rate limit. Mapped to HTTP 429 by the global handler. */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
