package com.localbuddy.pricing;

/** How VAT on the platform's commission is treated for a given host. */
public enum CommissionVatTreatment {
    /** Domestic VAT charged; host reclaims it (VAT-registered NL host). */
    STANDARD,
    /** Domestic VAT charged but the host cannot reclaim it (KOR / not registered). */
    NOT_REGISTERED,
    /** No VAT charged; the EU-registered host self-accounts (reverse charge). */
    REVERSE_CHARGE,
    /** No EU VAT applies (non-EU host). */
    OUT_OF_SCOPE
}
