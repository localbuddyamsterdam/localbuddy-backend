package com.localbuddy.invoice;

public enum InvoiceType {
    /** Platform's commission charged to the host. */
    COMMISSION,
    /** Platform's service-fee receipt issued to the customer. */
    SERVICE_FEE_RECEIPT,
    /** Statement to the host explaining a payout (price − commission − VAT). */
    PAYOUT_STATEMENT
}
