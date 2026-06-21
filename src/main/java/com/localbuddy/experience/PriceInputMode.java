package com.localbuddy.experience;

/** Which price a host typed when creating an experience; the other is computed from VAT. */
public enum PriceInputMode {
    /** Host typed the price the customer pays (VAT-inclusive). */
    GROSS,
    /** Host typed the net amount they want before VAT. */
    NET
}
