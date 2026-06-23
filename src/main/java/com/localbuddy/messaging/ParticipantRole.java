package com.localbuddy.messaging;

/**
 * A participant's role within a conversation, and the role a message was sent as.
 * Drives how a sender is labelled (ADMIN messages show as "Admin (Name)").
 */
public enum ParticipantRole {
    CUSTOMER,
    HOST,
    ADMIN
}
