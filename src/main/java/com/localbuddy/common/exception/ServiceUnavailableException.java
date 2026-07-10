package com.localbuddy.common.exception;

/**
 * Thrown when an upstream/external dependency (OAuth provider, payment gateway, etc.) is
 * unreachable or failing, as opposed to the client sending a bad request. Maps to HTTP 503
 * so the caller knows to retry rather than "fix" their input.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
