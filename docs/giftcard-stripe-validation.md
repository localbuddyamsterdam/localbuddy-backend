# Gift-card payment — Stripe test-mode validation runbook

Pre-production validation of the three gift-card money paths against a **real Stripe test
environment**. The application logic (balance decrement, charge reduction, refund split,
webhook idempotency, full/sub-minimum coverage) is already covered deterministically by
`GiftCardCheckoutIT` with the Stripe boundary stubbed. This runbook covers the one thing that
test cannot: the **real Stripe API contract** — that Stripe accepts the reduced charge, rejects
sub-minimum amounts, settles a real refund, and delivers a real `checkout.session.completed`
webhook that activates a purchased card.

Run this once before relying on the feature in production, after every Stripe SDK upgrade, and
whenever the checkout/refund/webhook code changes.

---

## 0. Prerequisites

- **Stripe test keys** (Dashboard → Developers → API keys, *test mode*): `sk_test_…`.
- **Stripe CLI** — `brew install stripe/stripe-cli/stripe` is unavailable on this Mac (no
  Homebrew); download the macOS arm64 tarball from
  https://github.com/stripe/stripe-cli/releases and put `stripe` on `PATH`. Then `stripe login`.
- **JDK 21** + the local throwaway Postgres (see project memory `local-dev-run-setup`).
- A **fresh DB** so Flyway applies cleanly through V24:
  ```bash
  ~/pg-localbuddy/pg/bin/psql -h localhost -p 5432 -d postgres -c "CREATE DATABASE localbuddy_stripeval"
  ```

## 1. Start the webhook forwarder (gives you the signing secret)

```bash
stripe listen --forward-to localhost:8080/api/public/payments/webhooks/stripe
# → prints:  Ready! Your webhook signing secret is whsec_xxxxxxxx  (copy this)
```

Leave this running in its own terminal. It prints every event it forwards — your live trace.

## 2. Boot the app against Stripe test mode

```bash
export JAVA_HOME=/Users/airala/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export DB_URL=jdbc:postgresql://localhost:5432/localbuddy_stripeval
export DB_USERNAME=$(whoami) DB_PASSWORD=postgres
export JWT_SECRET=local-validation-secret-please-change-0123456789
export SPRING_PROFILES_ACTIVE=dev EMAIL_PROVIDER=console
export STRIPE_SECRET_KEY=sk_test_...                       # your test secret key
export STRIPE_WEBHOOK_SECRET=whsec_...                     # from step 1
# Optional: point success/cancel at anything; the hosted page is what we drive.
sh mvnw -q spring-boot:run
```

Wait for `Started LocalbuddyBackendApplication`. Flyway should report it is at **version 24**.

> Throughout, a gift card is redeemed by passing its `code` as `giftCardCode` on a checkout
> request. Completing a Stripe Checkout session means **opening its `checkoutUrl` in a browser
> and paying with test card `4242 4242 4242 4242`, any future expiry, any CVC/postcode.** The CLI
> from step 1 then forwards the real `checkout.session.completed` event to the app.

Handy SQL (run in a third terminal):
```bash
psql() { ~/pg-localbuddy/pg/bin/psql -h localhost -p 5432 -d localbuddy_stripeval "$@"; }
psql -c "SELECT code,status,balance FROM gift_cards ORDER BY created_at DESC LIMIT 5;"
psql -c "SELECT id,payment_status,amount,gift_card_amount,refunded_amount,provider_payment_intent_id FROM payments ORDER BY created_at DESC LIMIT 5;"
psql -c "SELECT provider_event_id,event_type,processed FROM payment_webhook_events ORDER BY received_at DESC LIMIT 10;"
psql -c "SELECT gift_card_id,booking_id,amount FROM gift_card_redemptions ORDER BY created_at DESC LIMIT 5;"
```

---

## 3. Flow 3 first — Purchase a gift card (you need a card to redeem)

Buying a gift card requires auth. Get a token:
```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"yourpassword"}' | jq -r '.accessToken // .token')
```

**Purchase** (mints the card `PENDING_PAYMENT`, returns a Stripe checkout URL):
```bash
curl -s localhost:8080/api/gift-cards/purchase -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":150.00,"currency":"EUR"}' | jq
# → { "code":"LB-XXXX-…", "status":"PENDING_PAYMENT", "checkoutUrl":"https://checkout.stripe.com/…" }
```

✅ **Assert (before paying):** card is `PENDING_PAYMENT`, and it is **not usable** — a redemption
attempt must fail:
```bash
psql -c "SELECT status,balance FROM gift_cards WHERE code='LB-XXXX-…';"   # PENDING_PAYMENT
```

Now **open the `checkoutUrl`, pay with 4242…**. Watch the `stripe listen` terminal forward
`checkout.session.completed`.

✅ **Assert (after paying):**
- `stripe listen` shows `checkout.session.completed` → `200`.
- `gift_cards.status = ACTIVE`, balance `150.00`.
- `payment_webhook_events` has one row for that event id, `processed = true`.

✅ **Webhook idempotency / retries:** re-deliver the same event and confirm it is a no-op:
```bash
stripe events resend <evt_id_from_listener>      # or: trigger a Stripe-side retry
```
The second delivery must return `200` "Duplicate webhook ignored", the card stays `ACTIVE`, and
no second webhook-event row appears. (The DB unique constraint `ux_payment_webhook_provider_event`
backs this.)

Keep this card's `code` (call it `$GC`) and balance (`150.00`) for the redemption tests.

---

## 4. Flow 1 — Redeem at checkout

These use the **guest checkout** path (public, no auth). Create a guest booking first via your
normal booking flow and note its `bookingReference` + `guestEmail`; or use an authenticated
booking with `POST /api/payments/checkout` and a bearer token. The gift-card behaviour is
identical on both paths.

### 4a. Partial coverage — Stripe charges the remainder
Booking total e.g. **€100**, redeem `$GC` (balance 150 → covers 100 fully)… to test *partial*,
use a booking **larger than the card balance**, or a card with a small balance. With a €40 card
on a €100 booking:
```bash
curl -s localhost:8080/api/public/guest-payments/checkout -H 'Content-Type: application/json' \
  -d "{\"bookingReference\":\"$REF\",\"guestEmail\":\"$EMAIL\",\"giftCardCode\":\"$GC40\"}" | jq
# → paymentStatus PROCESSING, a checkoutUrl
```
✅ **Assert:** in the Stripe Dashboard (or `stripe listen`), the Checkout session's amount is the
**remainder = total − giftCardAmount** (e.g. €60 + service fees, **not** the full total). The
gift card balance is already decremented by 40 (`gift_cards.balance`), the payment row shows
`gift_card_amount = 40`, status `PROCESSING`.

Pay with 4242…. ✅ After the webhook: payment `PAID`, booking confirmed, **one**
`gift_card_redemptions` row for the booking (balance was decremented exactly once, at reserve).

### 4b. Full coverage — Stripe charge is €0, no hosted page
Redeem a card whose balance ≥ the full payable amount (e.g. the €150 `$GC` on a €100 booking):
```bash
curl -s localhost:8080/api/public/guest-payments/checkout -H 'Content-Type: application/json' \
  -d "{\"bookingReference\":\"$REF2\",\"guestEmail\":\"$EMAIL2\",\"giftCardCode\":\"$GC\"}" | jq
# → paymentStatus PAID immediately, NO checkoutUrl needed
```
✅ **Assert:** **no** Checkout session is created in Stripe at all (amount-0 is never sent —
Stripe would reject it). Payment `PAID`, booking confirmed, gift balance reduced by the payable
amount, one redemption row.

### 4c. Sub-minimum remainder — platform absorbs the few cents *(fix for finding #1)*
Redeem a card that leaves a remainder **below Stripe's €0.50 minimum** (e.g. a €102.73 card on a
~€103.03 payable → €0.30 remainder):
```bash
# create / fund a card so that (payable − balance) is between 0.01 and 0.49
```
✅ **Assert:** like 4b — **no** Checkout session is created, payment goes straight to `PAID`,
booking confirmed. Before the fix this remainder would be sent to Stripe and rejected
(`amount_too_small`) and the booking would be stuck. Confirm there is **no** Stripe error in the
app log and **no** `amount_too_small` in the Stripe Dashboard.

---

## 5. Flow 2 — Refund to card

Take a **partially gift-paid, PAID** booking from 4a (gift €40, cash €60 captured by Stripe).
Cancel it ≥ 24 h before the slot start (current policy → 100% refund) via your booking
cancellation endpoint.

✅ **Assert amounts reconcile:**
- `gift_cards.balance` increases by the **gift share** (€40) and a `DEPLETED` card flips back to
  `ACTIVE`.
- In Stripe (Dashboard → Payments → the PaymentIntent) there is a **refund of the cash share
  only** (€60) — never more than Stripe captured.
- `payments.refunded_amount = 100.00` (gift 40 + cash 60). Note the status is
  `PARTIALLY_REFUNDED` even though fully refunded, because the Stripe leg (60) is < the payment
  total incl. service fee — see finding (4) below.

Also validate **late cancellation (0% policy)**, < 24 h before start: nothing returns to the
card, no Stripe refund, payment stays `PAID`.

---

## 6. Pass criteria

| # | Path | Expected |
|---|------|----------|
| 1 | Purchase | card `PENDING_PAYMENT` → unusable; webhook → `ACTIVE`; re-delivered event is a no-op |
| 2 | Partial redeem | Stripe charged `total − gift`; balance −gift once; one redemption after webhook |
| 3 | Full redeem | no Stripe session; `PAID` immediately |
| 4 | Sub-minimum | no Stripe session; `PAID`; no `amount_too_small` |
| 5 | Refund (100%) | gift share → card, cash share → Stripe, `refunded_amount` reconciles to total |
| 6 | Refund (0%) | nothing returned, no Stripe refund, stays `PAID` |
| 7 | Webhook retry | duplicate event → `200` "Duplicate webhook ignored", no double effect |

---

## 7. Known issues to keep in mind while testing (from the static audit)

2. **`REFUND_FAILED` has no recovery path** and the gift share is credited *before* the Stripe
   refund. If the Stripe refund call fails, the card keeps the returned gift balance, the payment
   is stuck `REFUND_FAILED`, and re-running cancellation no-ops. There is no admin "retry refund"
   (unlike `retryPayout`). To reproduce, use a test PaymentIntent that can't be refunded.
3. **`cashRefund` is not capped at the captured cash** (`amount − giftCardAmount`). Safe under the
   current pure-percentage refund policy; would over-refund (Stripe rejects) under any future
   flat-fee/floor policy.
4. **Status label**: a gift+cash booking refunded in full is labelled `PARTIALLY_REFUNDED` because
   only the Stripe leg is compared to the payment total. Cosmetic, but affects reporting.
5. **Two webhook entry points**: the live controller uses `handleStripeWebhookEvent`; a second
   `handleStripeWebhook(StripeWebhookRequest)` path skips `finalizePaidBooking`. Confirm the
   latter is unused.
