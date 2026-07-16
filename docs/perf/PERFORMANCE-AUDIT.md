# LocalBuddy Performance Audit — 2026-07-15

Method: 7 parallel read-only audit agents over the backend, database, and Angular frontend,
following the repo perf rules (no optimizing without evidence, preserve API behavior, schema
changes via Flyway, no blind caching/indexes). Applied changes are **behavior-preserving** and
**compile-verified** (`mvnw compile` green, `ng build` green). They are **not yet load-tested** —
per the rules, no before/after latency numbers are claimed; each item lists how to verify.

Applied changes are **uncommitted** for review. Migrations are at **V45** (not V28 as CLAUDE.md says).

---

## ✅ Applied — backend (behavior-preserving, compiled)

| # | Fix | File | Effect |
|---|-----|------|--------|
| 1 | **Cover-photo N+1** — batch cover lookup for list mapping (new `findCoverPhotosByExperienceIds` + `toResponseList` + delegating `toResponse` overload); all 7 list sites routed through it | `ExperienceService.java`, `ExperiencePhotoRepository.java` | Catalog/search page of N experiences: **N cover queries → 1**. Hottest public path. Single-experience callers unchanged (1 query each). |
| 2 | **my-bookings N+1** — `@EntityGraph` on the two user/host booking finders (same paths the admin finders already use) | `BookingRepository.java` | Booking list of N: **~5N+1 → 1** query. |
| 3 | **Bundle payment-group N+1** — `@EntityGraph` on `findByPaymentGroupIdOrderByCreatedAtAsc` | `PaymentRepository.java` | Public bundle status view (polled): **~3N+1 → 1**. |
| 4 | **Checkout duplicate query** — merged two identical payment lookups into one (semantics preserved) | `PaymentService.java` | Guest checkout: 2 round-trips → 1. |
| 5 | **Upcoming-booking reminder N+1** — `@EntityGraph` on `findConfirmedStartingBetween` | `BookingReminderRepository.java` | Reminder tick: ~3–4 queries/booking → 1. |
| 6 | **Underbooked-slot notifier N+1** — `@EntityGraph` (localProfile, localProfile.user, experience) | `AvailabilitySlotRepository.java` | Sweep tick: 3 lazy loads/slot → 0 extra. |
| 7 | **Wishlist reminder N+1** — `@EntityGraph` (user, experience) on the candidate finder | `WishlistItemRepository.java` | Reminder tick: 2 lazy loads/item → 0 extra. |
| 8 | **Outbound timeouts** — connect 5s / read 8s on Facebook + Google token verifiers and the Frankfurter currency client (were unbounded → could hang a request thread) | `FacebookTokenVerifier.java`, `GoogleTokenVerifier.java`, `FrankfurterExchangeRateProvider.java` | Login/currency paths fail fast instead of hanging. |
| 9 | **ACS email client reuse** — build the Azure `EmailClient` once (lazy) instead of per send | `AzureEmailProviderService.java` | Removes per-message client construction on the notification thread. |

**Verify (all):** enable `hibernate.generate_statistics` / `logging.level.org.hibernate.SQL=DEBUG`,
hit the endpoint/tick before & after, assert the statement count is constant regardless of row count.
For timeouts, point the base URL at a black-hole host and confirm fast failure.

## ✅ Applied — frontend (behavior-preserving, built)

| # | Fix | File | Effect |
|---|-----|------|--------|
| F1 | `isFavorite` O(n) `includes` → O(1) `Set` (computed) | `favorites.service.ts` | Per-card favorite check on search/home grids is now O(1). |

---

## 🔬 MEASURE-FIRST — recommended, NOT applied (need EXPLAIN / metrics / product sign-off)

### Database indexes
See **`docs/perf/candidate-indexes.sql`** — 7 composite indexes (notifications, bookings,
reviews ×2, availability_slots, wishlist_items, messages). Highest ROI: notifications & bookings
(back schedulers) and the two review indexes (public traffic). Validate each with
`EXPLAIN (ANALYZE, BUFFERS)`, then move into a `V46+` migration (prefer `CREATE INDEX CONCURRENTLY`).

### Backend
- **Scheduler thread pool = 1.** ~12 `@Scheduled` processors serialize on one thread; a slow tick
  blocks all others. Add `spring.task.scheduling.pool.size: 4` (config-only). Verify no two jobs
  race on shared rows before raising.
- **AI endpoints block the request thread** (trip planner up to 300s read timeout). Consider an
  async job + poll, or a bounded dedicated pool, so slow generations can't starve Tomcat workers.
  (The Anthropic client itself is exemplary — bounded retries, read-timeouts never retried, daily cap.)
- **Unbounded public/admin list endpoints** (`/public/experiences`, per-experience reviews, admin
  lists) — add pagination. Contract change → coordinate with frontend.
- **Trending** loads the whole approved catalog and sorts in memory — push ranking to SQL with `LIMIT`.
- **BookingExpiry** runs the same payment query twice per booking; **BookingAutoCompletion** does an
  exists-query per due booking — both batchable (low urgency).
- **Hikari pool = 10 vs Tomcat 200 threads** and empty `@Async` executor config (unbounded queue) —
  load-test before tuning.
- **Stripe** uses SDK default timeouts (~80s read) — consider lowering for snappier failures.

### Frontend (verify visually on a running dev server)
- **Home scroll handler** (`home-concept`) is unthrottled → forces change detection + layout read on
  every scroll event. rAF-gate it and cache the `.searchbar` ref. Highest-traffic page.
- **Home trending rail** calls per-card methods (`cardDealPrice`/`modeChip`/`isFavorite`) in the
  template each CD — move to a memoized `trendingVm` computed (the pattern `search.component` already uses).
- **Reference-data caching** — `getCities`/`getCategories` refetched on nearly every view; add
  `shareReplay(1)` **with** a soft TTL + `clearCache()` invoked by the admin mutation handlers.
- **`getSimilar`/`getMoreByHost`** each refetch the entire experiences list — share one cached list.
- **Sequential subscribe waterfalls** (`search`, `experience-detail`) → `forkJoin`.
- **Images** — route raw `<img>` (home hero etc.) through the existing `lb-image` (or add width/height +
  `loading=lazy`) to remove layout shift.
- **Messages** polls every 2s — raise interval + pause on `document.hidden`.

---

## Verified clean (no action)
- Entity fetch types: every `@ManyToOne`/`@OneToOne` already explicit `LAZY`; `default_batch_fetch_size=100`.
- No harmful retry multiplication anywhere (only the AI client retries, single-layered).
- Frontend: OnPush universal, `@for` tracking sound, routes lazy, lean deps, no subscription leaks found,
  search input already debounced.
