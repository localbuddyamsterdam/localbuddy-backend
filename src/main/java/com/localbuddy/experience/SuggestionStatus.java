package com.localbuddy.experience;

/** Lifecycle of a host-submitted category suggestion. */
public enum SuggestionStatus {

    /** Awaiting admin review. */
    PENDING,

    /** An admin created a category from it (see resultingCategoryId). */
    APPROVED,

    /** An admin decided not to add it. */
    DISMISSED
}
