package com.localbuddy.pricing;

/**
 * VAT position of a host, which drives both the experience-leg VAT and the
 * commission-leg VAT treatment.
 */
public enum HostVatStatus {
    /** VAT-registered in the platform's home country (NL): charges VAT, reclaims commission VAT. */
    NL_REGISTERED,
    /** Not VAT-registered (KOR / private): no VAT on the experience; bears (cannot reclaim) commission VAT. */
    NL_NOT_REGISTERED,
    /** VAT-registered business in another EU country: commission is reverse-charged. */
    EU_OTHER_REGISTERED,
    /** Outside the EU: commission is outside the scope of EU VAT. */
    NON_EU
}
