package com.localbuddy.giftcard;

public enum GiftCardStatus {
    /** Created but not yet paid for — not spendable until the purchase payment succeeds. */
    PENDING_PAYMENT,
    ACTIVE,
    DEPLETED,
    CANCELLED,
    EXPIRED
}
