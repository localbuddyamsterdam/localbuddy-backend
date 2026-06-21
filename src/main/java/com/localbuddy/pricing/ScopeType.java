package com.localbuddy.pricing;

/**
 * Scope of a commission / service-fee rule, in increasing precedence. A more
 * specific scope overrides a broader one when both are active and in-window.
 */
public enum ScopeType {
    PLATFORM,
    CITY,
    CATEGORY,
    HOST,
    EXPERIENCE
}
