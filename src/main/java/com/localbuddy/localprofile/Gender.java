package com.localbuddy.localprofile;

/**
 * Host-declared gender. Optional on a local profile (may be null when the host
 * has not set it). {@link #PREFER_NOT_TO_SAY} is an explicit opt-out that is
 * excluded from a specific-gender filter, just like a null value.
 */
public enum Gender {
    MALE,
    FEMALE,
    NON_BINARY,
    PREFER_NOT_TO_SAY
}
