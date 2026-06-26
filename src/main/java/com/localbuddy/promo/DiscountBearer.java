package com.localbuddy.promo;

/** Who bears the cost of a promo/voucher discount. */
public enum DiscountBearer {

    /** Host earns on the post-discount price — the host absorbs the discount (default, legacy behaviour). */
    HOST,

    /** Host is paid on the full pre-discount price; the platform absorbs the whole discount. */
    PLATFORM,

    /** Platform and host split the discount by {@code platformSharePercentage}. */
    SPLIT
}
