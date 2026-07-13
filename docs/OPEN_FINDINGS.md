# LocalBuddy backend — findings (all sessions)

Consolidated findings across every working session, grouped by area. Risk key:

- 🔴 **Red — risky / must-have.** Security, privacy, money-loss, or compliance exposure.
- 🟠 **Amber — middle.** Real correctness/robustness/operational gaps; recoverable.
- 🟡 **Yellow — nice-to-have.** Cosmetic, hygiene, defensive hardening, or backlog.

Status key: ✅ **fixed 06-30** (this session, compiles + tests green) · ⬜ **open** ·
`[decision]` intentional choice. Companion detail: `docs/giftcard-open-findings.md`,
`docs/giftcard-stripe-validation.md`.

> **2026-06-30 fix pass:** all code-fixable Red/Amber/Yellow items below were addressed except the
> live-Stripe validation (deliberately deferred). Verified by `clean test-compile`, the 22 unit
> tests, and the 7-test `GiftCardCheckoutIT` (full Spring context + real Postgres), all green.

---

## 1. Security & access control

- ✅ **`/api/host/**` now role-gated to `LOCAL` + method security enabled.** `SecurityConfig` adds
  `.requestMatchers("/api/host/**").hasRole("LOCAL")` and `@EnableMethodSecurity`. A non-host
  authenticated user no longer passes the URL gate on host endpoints. **fixed 06-30**
- ✅ **401/403 now return the uniform `ErrorResponse` JSON** (custom `authenticationEntryPoint` +
  `accessDeniedHandler`), instead of Spring's default body. **fixed 06-30**
- ✅ **SUPPORT-reads-any-booking IDOR** — already remediated before this session
  (`BookingService.getBookingById:627` scopes SUPPORT to conversation participants). Re-verified 06-30.

### 🟠 Amber
- ✅ **Rate limiting hardened.** Now returns **429** (`RateLimitExceededException`, was 400);
  `X-Forwarded-For`/`X-Real-IP` are no longer trusted by default (`ClientIpResolver` gated by
  `app.rate-limit.trust-forwarded-headers`, default false → uses `getRemoteAddr()`); Redis
  fail-open is now logged instead of swallowed silently. **fixed 06-30**
  - ⬜ *Ops:* if deployed behind a trusted proxy/LB, set `app.rate-limit.trust-forwarded-headers=true`.
- ✅ **Public enumeration throttled.** `GET /api/public/gift-cards/{code}/balance` and
  `POST /api/public/promo-codes/validate` now call `RateLimitService.checkPublicApiLimit` per client
  IP (they previously skipped it). **fixed 06-30**
- ✅ **CORS exposed headers** added (`Location, Content-Disposition, X-Total-Count, Retry-After`).
  ⬜ *Ops:* set `app.cors.allowed-origins` to the real production origins before the FE goes live
  (currently env-driven, defaults to `localhost:3000`).

---

## 2. Privacy / GDPR

- ✅ **GDPR `anonymize` now scrubs host data.** `GdprService.anonymize` additionally clears the host
  `LocalProfile` (legal name, address, zip, phone, DOB, **VAT/tax IDs, bank**) and nulls the comment
  text of reviews authored by the user — not just the `User` row. **fixed 06-30**

---

## 3. Money, payments & gift cards
Detail in `docs/giftcard-open-findings.md`.

- ✅ **Gift-card full-coverage checkout crash fixed.** Self-invocation bypassed `@Transactional` on
  `completeWithoutStripeCharge` → `LazyInitializationException`. Now routed via a `@Lazy` self-ref.
  **fixed 06-30** (proven by `GiftCardCheckoutIT`).
- ✅ **Sub-minimum gift-card remainder absorbed** (a €0.01–€0.49 remainder below Stripe's floor
  completes without Stripe instead of being rejected). **fixed 06-30**
- ✅ **Refund reorder + cap + reconciled status.** Stripe cash refund now happens **before** the gift
  return (so a Stripe failure can't strand the cash leg / leaves nothing credited); `cashRefund` is
  capped at captured cash (`amount − giftCardAmount`); status comes from the reconciled gift+cash
  total. `refundFullPayment` is now gift-aware too (shared `applyRefundSplit`). **fixed 06-30**
- ✅ **Dead second webhook path removed** (`handleStripeWebhook(StripeWebhookRequest)` +
  `markPaymentPaidFromCheckoutSession` + the unused DTO) — the live path is `handleStripeWebhookEvent`.
  **fixed 06-30**

### 🔴 Red — open (deferred by request)
- ⬜ **Gift-card Stripe paths never exercised against real Stripe.** Validated only with Stripe
  stubbed. Run `docs/giftcard-stripe-validation.md` against Stripe test mode before relying in prod.

### 🟡 Yellow — open
- ⬜ **giftcard↔payment package cycle** — cosmetic (no Spring bean cycle); tidy via an interface/event
  when convenient. Left as-is.

---

## 4. Safety & data integrity

- ✅ **SOS / emergency data validated.** `SosRequest` lat/long now `@NotNull` +
  `@DecimalMin/@DecimalMax` (±90 / ±180); emergency `contactPhone` now `@Pattern` (E.164-ish).
  **fixed 06-30**
- ✅ **Reviews uniqueness enforced in the DB.** `V25__reviews_uniqueness.sql` adds a unique index on
  `(booking_id, direction)`, matching the app-level guard. **fixed 06-30**

---

## 5. API contract & client integration

- ✅ **Error contract standardised.** `ErrorResponse` gains a machine-readable `code`
  (`VALIDATION_ERROR`, `RATE_LIMITED`, `UNAUTHORIZED`, `FORBIDDEN`, …) and a structured
  `fieldErrors[]` for validation failures; 401/403 use the same shape. **fixed 06-30**

---

## 6. Schema, migrations & deployment
Supabase + repo were at Flyway **v24**; this session adds **V25** (reviews uniqueness) — apply on next deploy.

### 🔴 Red — operational (no code change)
- ⬜ **Every future Supabase migration MUST pin Flyway to `public`** (`&currentSchema=public` +
  `SPRING_FLYWAY_SCHEMAS=public` + `SPRING_FLYWAY_DEFAULT_SCHEMA=public`, session pooler :5432),
  else Flyway lands in the `extensions` schema and fails. Background in memory `supabase-schema-reconciliation`.

### 🟠 Amber — operational rule
- ⬜ **Never edit an already-applied migration (incl. V1)** — checksum mismatch breaks `migrate`.
  Corrections go in a *new* migration.

### 🟡 Yellow
- ⬜ **`mvnw spring-boot:run` is flaky** — use the fat-jar run path for migrations/boots.

---

## 7. Dev / ops / secrets hygiene

- ✅ **`.claude/` and `.DS_Store` now gitignored** — closes the accidental-commit path for the local
  secrets in `.claude/settings.local.json`. **fixed 06-30**
- 🔴 ⬜ **ROTATE the Supabase DB password.** It sat in plaintext in `.claude/settings.local.json`
  (now gitignored, and `.claude/` was never tracked) — but treat it as exposed and rotate it.
  **User action required.**
- ⬜ Local throwaway DB `localbuddy` is on the OLD lineage — use a *fresh* DB for local verification.

---

## 8. Feature backlog (planned)

- ⬜ 🟡 **Apple / Google Wallet passes** for CONFIRMED bookings (Google first). Additive, no schema
  change. Detail in memory `wallet-passes-feature`. `[decision: backlog]`

---

## Intentional choices (NOT findings)
- Host tax/VAT/DAC7 fields **optional at onboarding** (editable later via `/tax-info`). `[decision]`
- Hosts **not** restricted to approved `experienceCities` (any active city). `[decision]`
- Primary `category` **optional on create** (required on update). `[decision]`
- Gift cards = stored value: no min spend, no expiry, never cashable, multi-use. `[decision]`
- Sub-minimum gift-card remainder **absorbed** by the platform. `[decision]`
