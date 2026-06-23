package com.localbuddy.messaging;

/** What kind of conversation this is. */
public enum ConversationType {
    /** Peer thread between a customer and a host. Admins may monitor and take over (transparently). */
    CUSTOMER_HOST,
    /** Admin-initiated private side conversation with a single customer. */
    ADMIN_CUSTOMER,
    /** Admin-initiated private side conversation with a single host. */
    ADMIN_HOST
}
