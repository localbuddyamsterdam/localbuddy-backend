# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository location & branch (read first)

- All development happens in the **OneDrive canonical clone** (`C:\Users\reddy\OneDrive\Documents\GitHub\localbuddy-backend`) on branch **`feature/private-tour-buyout`** — **not** the `IdeaProjects\localbuddy-backend` fork, which is stale.
- Pushing `feature/private-tour-buyout` triggers the Azure deploy (see below). `main` is the nominal default branch but is not what's deployed.

## Build & run

Requires **JDK 21 (Temurin)**. IntelliJ's bundled JBR (25) silently breaks Lombok annotation processing, so set `JAVA_HOME` to a real Temurin 21 before building:

```powershell
$env:JAVA_HOME = "C:\Users\reddy\.jdks\temurin-21.0.11"
.\mvnw.cmd -B -ntp -DskipTests compile      # fast compile check (add -o once deps are cached)
.\mvnw.cmd -B -ntp -DskipTests package       # build the deployable jar
.\mvnw.cmd test                              # full test suite (src/test, Spring Boot test starters + JUnit 5)
.\mvnw.cmd test -Dtest=BookingServiceTest#createsBooking   # single test / method
.\mvnw.cmd spring-boot:run                    # run locally on :8080
```

- If OneDrive is actively syncing, it can lock `target/` mid-build — pause OneDrive if you hit file-lock errors.
- Running the app (and most tests) needs a Postgres datasource — there is no embedded/Testcontainers DB. Minimum env to boot: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`. Profile defaults to `dev`, which auto-seeds a dev admin (`admin@test.com` / `Password@123`).

## Deploy (Azure)

- GitHub Actions (`.github/workflows/azure-webapp.yml`) builds and deploys to **Azure App Service** (France Central) on push to `feature/private-tour-buyout`. DB is Supabase Postgres.
- **Critical gotcha:** the workflow deploys the jar, but the running app does **not** reliably pick it up. After the deploy succeeds you must do a **full Stop → Start** in the Azure Portal (wait for status "Stopped" before Start) — a plain Restart or the deploy's own restart is not enough. Cold start is ~4–5 min, and the app returns transient 403s on all endpoints while booting; poll `/actuator/health` until `UP`.
- `az` CLI is not installed locally. To read runtime errors, use the Azure Portal **Log stream**. On any 500, get that stack trace *before* theorizing about causes.

## Architecture

Spring Boot 4 / Java 21 **modular monolith**, **package-by-feature** under `com.localbuddy.*` (~40 feature packages: `booking`, `payment`, `experience`, `availability`, `notification`, `localprofile`, `payout`, `invoice`, `review`, `messaging`, `waitlist`, `attendance`, `giftcard`, `promo`, `referral`, `wallet`, `auth`, etc.). Each package holds its own entity + repository + service + controller + DTOs; cross-feature calls go service-to-service.

**Persistence — Flyway owns the schema.** `spring.jpa.hibernate.ddl-auto=validate`, so Hibernate never creates/alters tables. Any schema change requires a **new `V<n>__*.sql` migration** in `src/main/resources/db/migration` (currently through V28). The DB is the source of truth for invariants: there are many `CHECK`/`UNIQUE`/partial-unique constraints (e.g. a guest booking requires `guest_name + guest_email + guest_phone`; a guest can't double-book the same slot). Validate request input to match these — an unmatched constraint used to surface as a 500 (see error handling).

**Security.** Stateless JWT (jjwt), URL-pattern rules in `auth/SecurityConfig`: a `permitAll` list (`/api/auth/**`, `/api/public/**`, `/api/experience-categories/**`, `/api/cities/**`, `/illustrations/**`, health, swagger), `/api/admin/**` + `/api/users/**` require `ROLE_ADMIN`, everything else authenticated. `JwtAuthenticationFilter` sets the principal to the user UUID (`authentication.getName()`).

**Error handling.** `common/exception/GlobalExceptionHandler` (`@RestControllerAdvice`) is the single place that maps exceptions to `ErrorResponse` + status: domain `BadRequestException`→400 / `ResourceNotFoundException`→404, bean validation→400, `DataIntegrityViolationException`→409 (unique) / 400 (check/FK) with friendly per-constraint messages keyed on the Hibernate constraint name, `IllegalArgumentException`/type-mismatch→400, `ServiceUnavailableException`→503 (upstream provider down), and a catch-all→500. Prefer throwing these typed exceptions from services rather than letting raw ones escape.

**Asynchronous processors.** A lot of behavior is **time/poll-driven, not synchronous** — `@Scheduled` loops poll for work at intervals set by `app.*.processor-delay-ms` in `application.yaml`: notification delivery (`notification/NotificationProcessingService`, ~10s), booking-payment expiry (~60s), reminders (wishlist/abandoned/upcoming), underbooked-slot cancellation, booking auto-complete, payout runs, check-in retention. A booking confirmation email, for example, is created as a `PENDING` notification and sent by the processor on its next tick — not inline.

**Notifications.** `NotificationService.createNotification` is the single funnel (dedup by `dedupeKey`; channels `EMAIL` / `IN_APP` / `WHATSAPP` / `SMS`). It auto-brands any EMAIL lacking a bespoke `htmlBody` via `EmailTemplateService.renderGeneric`. `BOOKING_CONFIRMED` uses a rich template + a calendar `.ics` invite attached at send time. Email delivery goes through `EmailProviderService` (`app.email.provider` = `console` for local logging, `azure` for ACS). SMS is enum-only (skipped); WhatsApp Business sending is coded but **dormant** unless `WHATSAPP_*` env is set (the free `wa.me` click-to-chat builder always works).

**Financial engine.** Pricing/settlement is config-driven in `application.yaml` under `app.*`: platform commission (default 20%, capped by `max-commission-rate`), service fee, age-band multipliers (adult/teen full, child half, infant free), VAT (NL 21%, merchant-of-record model), payout scheduling, and PDF invoices (openhtmltopdf). Money flows: booking → Stripe checkout → webhook → `payment/PaymentService.finalizePaidBooking` → host ledger + invoice + promo/referral redemption.

**External integrations are env-gated** — blank env var means the feature is off/no-op, so the app boots without any of them: Stripe (`app.payments.stripe.*`), ACS email (`app.azure.communication`), Azure Blob media (`app.media.azure`), Google/Apple Wallet passes, Google social login, Anthropic AI (`app.ai.anthropic`). Config classes live in each feature package (or `config/`).

## Conventions & gotchas

- **Schema changes → new Flyway migration**, never Hibernate DDL. Don't renumber or edit applied migrations.
- **Money is `BigDecimal`**; times are `Instant` stored/compared in **UTC** (`hibernate.jdbc.time_zone=UTC`).
- `open-in-view=false` — lazy associations only load inside a `@Transactional` boundary; touch what you need before returning from the service.
- Business rules are overwhelmingly **config values in `application.yaml`** (fees, expiries, geofence, reminder offsets) — change config before adding code.
- `logging.level.com.localbuddy=DEBUG` is on; use it, and the Azure Log stream, to diagnose.
- Pre-launch hardening still open: set `SPRING_PROFILES_ACTIVE=prod` and rotate the seeded dev admin, add a verified email domain + DKIM.
