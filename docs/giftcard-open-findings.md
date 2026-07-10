# Gift-card payment — open findings

Findings from the pre-production validation of the gift-card money paths (2026-06-26). The two
items below the line were **fixed** during validation; the numbered findings are **open** (not
fixed — validation scope was "validate + fix the sub-minimum edge"). Companion docs:
`giftcard-stripe-validation.md` (real-Stripe runbook), and the deterministic regression test
`src/test/java/com/localbuddy/giftcard/GiftCardCheckoutIT.java`.

Severity key: 🔴 high (will hit prod) · 🟠 medium (operational, recoverable) · 🟡 low/cosmetic.

---

## Already fixed (for context)

- 🔴 **Self-invocation bypassed `@Transactional` on the no-Stripe completion.** `createCheckout`
  (non-transactional by design — must not hold a DB tx across the Stripe call) invoked its
  `@Transactional` siblings on `this`, so the no-Stripe finalize ran with no session →
  `LazyInitializationException` on `booking.getAppliedPromoCodes()` for every authenticated
  full-gift-coverage checkout. Fixed with a `@Lazy` self-reference
  (`self.completeWithoutStripeCharge(...)` / `self.releaseGiftCardOnFailure(...)`).
- 🔴 **Sub-minimum Stripe charge.** A gift card leaving a €0.01–€0.49 remainder (below Stripe's
  ~€0.50 floor → `amount_too_small`) is now absorbed and completes without Stripe
  (`shouldCompleteWithoutStripeCharge`, threshold `app.payments.stripe.minimum-charge:0.50`).

---

## Open findings

### 1. 🟠 `REFUND_FAILED` strands the cash refund; gift share credited before the Stripe refund
`PaymentService.handleBookingCancellationPayment` (and `refundFullPayment`).

The gift share is returned to the card (`giftCardService.returnToCard`, ~line 685) **before** the
Stripe cash refund (`paymentCheckoutProvider.refundPayment`, ~line 704). If the Stripe refund
throws, the catch sets status `REFUND_FAILED` and commits — so the card keeps the returned gift
balance, but the cash refund never happened. Re-running cancellation no-ops at the `!= PAID`
guard (~line 656), and there is no admin retry (unlike payouts, which have `retryPayout`). Net:
customer is short the cash refund until someone intervenes manually; same shape in
`refundFullPayment`.

**Recommended fix:** order the Stripe refund *before* the gift-card return (or make the whole
unit atomic + re-entrant), and add an admin "retry refund" path mirroring `retryPayout`. Make
`REFUND_FAILED` re-drivable.

### 2. 🟡 `cashRefund` is not capped at the captured cash
`PaymentService.handleBookingCancellationPayment` (~lines 681–706).

`cashRefund` is `refundAmount − giftRefund` and is **not** bounded by the cash actually captured
on Stripe (`amount − giftCardAmount`). The provider's own guard only rejects refunds greater than
the *full* `payment.amount`, not the captured cash. This is currently **safe** only because the
refund policy is pure-percentage (`CancellationRefundPolicyService.calculateRefund` returns
`round(total × pct/100)`), so the cash leg stays within captured cash — except a ≤1-cent rounding
edge at extreme percentages. Any future non-percentage policy (flat fee, minimum-refund floor,
absolute tier) would over-refund and Stripe would reject it (→ finding #1's stranded state).

**Recommended fix:** cap `cashRefund` at `amount − giftCardAmount` and fold any residual into the
gift-card return.

### 3. 🟡 Fully-refunded gift+cash booking is labelled `PARTIALLY_REFUNDED`
`PaymentService.handleBookingCancellationPayment` (~line 710) + `StripePaymentCheckoutProvider.refundPayment`.

The final payment status comes from the Stripe refund result, which compares the **cash leg**
against the full `payment.amount`. For a gift+cash booking refunded in full, the cash leg is less
than the total, so the provider returns `PARTIALLY_REFUNDED` even though gift + cash = the whole
amount. Cosmetic, but it skews refund reporting/ops dashboards.

**Recommended fix:** decide status from the reconciled total (`refundedAmount >= amount` →
`REFUNDED`), not from the Stripe leg alone.

### 4. ✅ Two webhook entry points with divergent finalize logic — *routed through the same finalize*
`PaymentService.handleStripeWebhookEvent` (live) vs `handleStripeWebhook(StripeWebhookRequest)` (unwired).

The live controller (`StripeWebhookController`) uses `handleStripeWebhookEvent` →
`markPaymentPaidFromStripeSession` → `finalizePaidBooking` (records gift redemption + promo
redemption + invoice). A second path, `handleStripeWebhook(StripeWebhookRequest)` →
`markPaymentPaidFromCheckoutSession`, previously marked the payment paid but **skipped**
`finalizePaidBooking` (no gift-redemption audit row, no promo redemption). It is unused by any
controller.

**Resolved:** `markPaymentPaidFromCheckoutSession` now calls `finalizePaidBooking` too, so both
webhook paths share one finalisation. That finalisation was also hardened — its best-effort steps
(promo/referral redemption, host-ledger earning, gift-card redemption, invoices, confirmation
notification) now run after the confirming transaction commits, each isolated in its own
`REQUIRES_NEW` transaction (`PaidBookingFinalizer`), so a downstream failure can no longer roll back
or 500 an already-paid, confirmed booking. (The legacy DTO path is still otherwise unwired.)

### 5. 🟡 giftcard↔payment package cycle (cosmetic)
`GiftCardService` → `PaymentCheckoutProvider`, and `PaymentService`/`PaymentTransactionService` →
`GiftCardService`.

Compiles and runs — there is **no** Spring bean cycle. It is a package-dependency smell only.

**Recommended fix (tidy-later):** break it with a small interface in a shared package, or an
event, when convenient.

---

## Context that affects how these behave

- **Refund policy is all-or-nothing in production.** After V15 the only active policies are
  LOGGED_IN_USER 100% (≥24h) / 0% (<24h) and LOCAL/ADMIN 100%. The fractional split code (and the
  rounding edge in #2) is only reachable if an admin creates a fractional policy via the API.
- **Webhook idempotency is sound.** DB unique constraint
  `ux_payment_webhook_provider_event (provider, provider_event_id)` (V1 baseline) + single-tx
  processing means concurrent/retried duplicates roll back; domain guards (`PAID` /
  `PENDING_PAYMENT`) back it up. Not a finding — noted as validated.
