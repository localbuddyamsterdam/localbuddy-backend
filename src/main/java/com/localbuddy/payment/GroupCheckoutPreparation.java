package com.localbuddy.payment;

import java.util.List;

/**
 * Result of the transactional bundle-checkout preparation: the persisted group and its
 * member payments, with the lazy values needed by the Stripe call already initialized.
 */
public record GroupCheckoutPreparation(PaymentGroup group, List<Payment> payments) {
}
