# LocalBuddy — Frontend Handoff & API Reference

This document is the human-readable map of the backend API. It is **exhaustive** (every controller / endpoint) and calls out the **UX flows the frontend must implement carefully** (e.g. the guest-favourite two-call pattern).

> The backend also publishes a machine-readable contract. **Generate a typed client from it** (`openapi-typescript`, `orval`, or `openapi-generator`) so nothing is missed and backend changes surface as compile errors:
> - Swagger UI: `GET /swagger-ui.html`
> - OpenAPI JSON: `GET /v3/api-docs`
>
> This markdown is the orientation/flow doc; the OpenAPI spec is the source of truth for exact shapes. Regenerate the client whenever the backend changes.

---

## 1. Conventions (read first)

**Base URL:** all paths below are relative to the API host, e.g. `https://api.localbuddy.example` + `/api/...`.

**Auth:** stateless JWT.
- `POST /api/auth/login` (or `/signup` then `/login`, or `/social`) returns `accessToken`.
- Send it on every non-public call: `Authorization: Bearer <accessToken>`.
- Token lifetime is `app.security.jwt.access-token-expiration-minutes` (default **15 min**). There is currently **no refresh-token endpoint** — on `401` from an expired token, send the user back through login. (Flag for backend if you need refresh tokens.)
- The user's id is always taken from the token on the server — you never pass a user id in the body/path for "my" endpoints.

**Access tiers (enforced by URL prefix):**
| Prefix | Who |
|---|---|
| `/api/public/**`, `/api/auth/**`, `/api/cities/**`, `/api/experience-categories/**`, `/api/health`, `/swagger-ui/**`, `/v3/api-docs/**` | **Public** (no token) |
| `/api/admin/**`, `/api/users/**` | **ADMIN** role only |
| everything else | **Authenticated** (any logged-in user; some additionally require role `LOCAL` or booking-participant checks at the service layer) |

A guest hitting an Authenticated endpoint gets **401** (this is how "force login" works — see Wishlist flow). A non-admin hitting an admin endpoint gets **403**.

**Roles (`UserRole`):** `LOGGED_IN_USER` (traveler/customer), `LOCAL` (host), `ADMIN`, `SUPPORT`. Note "logged-in user" means a **traveler** specifically — hosts have role `LOCAL`.

**Error response** (all 4xx/5xx share this shape):
```json
{ "timestamp": "2026-06-24T01:23:45Z", "status": 400, "error": "Bad Request", "message": "human-readable reason", "path": "/api/..." }
```
Validation failures (`400`) put the first field error in `message`.

**Data types:** timestamps are ISO-8601 strings (`Instant`); money is JSON decimal (`BigDecimal`, 2 dp); ids are string UUIDs; `LocalDate` is `yyyy-MM-dd`.

**Pagination:** search endpoints are zero-based (`page` default `0`, `size` default `20`) and return `{ content, page, size, totalElements, totalPages }`. Most list endpoints return a plain array.

**Rate limiting:** public/guest endpoints (guest booking, guest payment, guest no-show, contact-us) are rate-limited per client IP → **429** when exceeded. Show a "try again shortly" message.

**Idempotency:** add-to-wishlist, batch-wishlist, start-conversation are idempotent (safe to retry). Most POSTs that create resources return `201`.

---

## 2. Critical UX flows

### 2.1 Auth & consent gating
1. `POST /api/auth/signup` → then `POST /api/auth/login` (signup does **not** return a token). Or `POST /api/auth/social` with a provider token.
2. After login, check `GET /api/consents/my-status`. Use `missingTravelerConsentTypes` / `missingLocalConsentTypes` to decide whether to show consent prompts before letting the user book (traveler) or host (local). Record with `POST /api/consents/accept-required-traveler` / `-local` (or per-type `/accept`). The server captures IP + User-Agent automatically.

### 2.2 Host onboarding lifecycle
`POST /api/local-profiles/me` (create) → edit with `PUT` → `POST /api/local-profiles/me/submit` → admin reviews. Drive the UI from `GET /api/local-profiles/onboarding-status` which returns `canEdit`, `canSubmit`, `canCreateExperience`, plus a human `message`. `approvalStatus` flows `DRAFT → SUBMITTED → (APPROVED | CHANGES_REQUESTED | REJECTED)`; editing an `APPROVED` profile auto-resubmits it. A host can only create experiences when **APPROVED**.

### 2.3 Booking + payment (logged-in)
1. Browse: `GET /api/public/experiences/search` (or `/advanced`), then `GET /api/public/experiences/{id}` and `GET /api/public/experiences/{id}/availability`.
2. Book + pay in one shot: `POST /api/bookings/checkout` (returns `{ booking, checkout }`; `checkout.checkoutUrl` is the **Stripe-hosted** page — redirect there). Or `POST /api/bookings` then `POST /api/payments/checkout`.
3. User pays on Stripe; Stripe calls the backend webhook server-to-server (you never call it). On return, **re-fetch** the payment (`GET /api/payments/{paymentId}`) and read `paymentStatus` (`PAID` / `FAILED` / refund states). Do not assume success from the redirect alone.

### 2.4 Guest booking + payment (no account)
1. `POST /api/public/guest-bookings` (requires `guestName/Email/Phone`, `acceptedTerms`, `consentVersion`). Returns a `bookingReference` (`LB-…`).
2. `POST /api/public/guest-payments/checkout` with `{ bookingReference, guestEmail }` → Stripe URL.
3. Guest later checks status: `POST /api/public/guest-payments/lookup` or `POST /api/public/guest-bookings/lookup` with `{ bookingReference, guestEmail }`. **Reference + email is the guest's identity** across booking, payment, and no-show.

### 2.5 ⭐ Guest favouriting (wishlist) — the two-call / forced-login pattern
The entire `/api/wishlist` API requires login; a guest gets **401**. So when a **logged-out** user taps the heart, the frontend must:
1. **Do NOT call the API.** Store the tapped `experienceId` in local storage (accumulate a list as they favourite more cards).
2. **Force a login/signup** (your nice modal).
3. **Immediately after successful auth**, call `POST /api/wishlist/batch` once with all locally-saved ids (max 100). It's idempotent and skips unknown/non-approved/already-saved ids.
4. Clear the local store.

For a **logged-in** user, render filled/empty hearts across listings by calling `GET /api/wishlist/ids` **once** (returns just the saved experience ids) and diffing against rendered cards — not `GET /api/wishlist/{id}/status` per card. Single add/remove use `POST`/`DELETE /api/wishlist/{experienceId}`.

### 2.6 Private (whole-slot) booking + experience pricing rules (surface as form validation)
- Experience `bookingMode`: `SHARED`, `PRIVATE_ALLOWED`, `PRIVATE_ONLY`. `privatePrice` (flat whole-slot price) is required for the two private modes; per-person `priceAmount` required for `SHARED`/`PRIVATE_ALLOWED`.
- **Private price cap (PRIVATE_ALLOWED):** `privatePrice` ≤ `maxGuests × priceAmount`, else 400.
- **Aggregator blocks private:** if `externalListingType == AGGREGATOR_PLATFORM`, mode must be `SHARED` (else 400). `NONE` / `OWN_WEBSITE_SOCIAL` allow private.
- Booking a private slot: set `privateBooking: true`. Blocked if the experience is aggregator-listed, if the slot already has any booked seat, or against booking-mode (use slot `privateBookingAvailable` to gate the UI). For private bookings `totalAmount` is the flat private price and `seatsBlocked` = slot capacity.
- `priceInputMode` `GROSS`/`NET` controls whether the entered per-person price is VAT-inclusive; both gross (`priceAmount`) and net (`priceNetAmount`) are returned.

### 2.7 Cancellation, refunds, slot management
- **Traveler cancel** (`/cancel-by-traveler`, reason required): refund **100% if ≥24h** before start, else **0%**.
- **Host cancel** (`/cancel-by-local`): **rejected within 24h** of start (400). Host/admin cancel = **100% refund**. (If a booking was made <24h before start, the host can never cancel it.)
- Only `PENDING_PAYMENT`/`CONFIRMED` bookings are cancellable.
- **Slot block/delete** (`/api/availability/{slotId}/block|unblock`, `DELETE`): rejected (400) if the slot has active bookings — the host must cancel those first (only possible >24h before start). Empty slots are freely blockable.
- (Refund percentages are admin-configurable policies keyed by actor + hours-before-start; the above are the seeded defaults.)

### 2.8 No-show reporting (48h window)
- Logged-in customer/host: `POST /api/no-show/bookings/{bookingId}/report` within **48h** of the experience start. Customer→host report is a **refund claim** (admin-reviewed); host→customer report is informational.
- Guest customer: `POST /api/public/guest-no-show/report` with `{ bookingReference, guestEmail, reason? }`.
- Admin approves/rejects (`/api/admin/no-show/...`); **host-no-show approval triggers a full refund**. Show the "report a no-show / claim refund" option only within 48h of start.

### 2.9 Geo check-in & host arrival (around start time)
Tied to the experience's meeting-point coordinate (the `latitude`/`longitude` on the experience). See §4.4 "Geo check-in & attendance" for full DTOs.
- **Guest check-in is optional but hard-gated.** Only enable the button inside the time window (15 min before → 15 min after start). On tap, request precise geolocation and `POST` lat/lng/accuracy. A **400** comes back with a ready-to-show message when they're too early/late, too far, too imprecise, or the host never set a meeting point — surface it as-is. Logged-in → `/api/bookings/{id}/check-in`; guests → `/api/public/check-in` with reference + email.
- **Host check-in is soft** (`/api/host/slots/{slotId}/check-in`, multipart, optional live photo). It always records; if outside the fence the response carries a `warning` + `distanceMeters` — show the warning, don't block. Host check-in fires a **`HOST_ARRIVED`** notification to confirmed guests.
- **Host attendance roster** (`GET /api/host/slots/{slotId}/attendance`) shows who's checked in nearby + each booking's `guestShowStatus`; the host marks `SHOWED`/`NO_SHOW` per booking (one mark per booking, party size irrelevant) via `/api/host/bookings/{id}/attendance`. This is operational ("whom to call first"), **not** the admin refund verdict.

### 2.10 Admin messaging takeover — render `senderLabel` verbatim
Conversations are participant-based and typed. In **every** `MessageResponse`, use the pre-formatted **`senderLabel`**: for admin-sent messages it's `"Admin (Sarah Chen)"`, otherwise the sender's plain name. **Do not build your own label** from `senderName`/`senderRole`. Admins can monitor all threads, reply/take over transparently (their messages appear to all participants as `Admin (Name)`), and open private side conversations with a single customer or host (`ConversationType` `ADMIN_CUSTOMER` / `ADMIN_HOST`).

### 2.11 Other gotchas
- **Waitlist:** being `NOTIFIED` when a seat frees up is **not a reservation** — it's first-come-first-served. Message accordingly.
- **Reviews** require the booking to be **COMPLETED**; one review per direction per booking; direction is set server-side.
- **Trip safety SOS** (`POST /api/trip-safety/bookings/{id}/sos`) emails support with location — wire it to an obvious in-trip button. Check-in/out/SOS are traveler-only.
- **Two "me" profile endpoints:** `GET /api/auth/me` (lean) vs `GET /api/account/me` (full, with timestamps).

---

## 3. Complete enum reference

> All values verified from source. Use the enum that matches the endpoint's module (note the two safety variants).

**Identity & users**
- `UserRole`: `LOGGED_IN_USER`, `LOCAL`, `ADMIN`, `SUPPORT`
- `UserStatus`: `ACTIVE`, `PENDING_VERIFICATION`, `SUSPENDED`, `DELETED`
- `SocialProvider`: `GOOGLE`, `FACEBOOK`, `APPLE`
- `ConsentType`: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `COMMUNITY_GUIDELINES`, `SAFETY_GUIDELINES`, `LIABILITY_ACKNOWLEDGEMENT`
- `DataDeletionStatus`: `REQUESTED`, `PROCESSED`, `REJECTED`
- `UserRestrictionType`: `ACCOUNT_SUSPENDED`, `BOOKING_BLOCKED`, `HOSTING_BLOCKED`

**Host profile**
- `LocalApprovalStatus`: `DRAFT`, `SUBMITTED`, `CHANGES_REQUESTED`, `APPROVED`, `REJECTED`, `BLOCKED`
- `LocalVerificationStatus`: `NOT_STARTED`, `ID_PENDING`, `ID_VERIFIED`, `MANUALLY_APPROVED`, `REJECTED`
- `VerificationStatus`: `NOT_STARTED`, `PENDING`, `PASSED`, `FAILED`, `MANUAL_REVIEW`
- `HostVatStatus`: `NL_REGISTERED`, `NL_NOT_REGISTERED`, `EU_OTHER_REGISTERED`, `NON_EU`

**Experience**
- `ExperienceStatus`: `DRAFT`, `SUBMITTED`, `APPROVED`, `REJECTED`, `PAUSED`, `BLOCKED`
- `BookingMode`: `SHARED`, `PRIVATE_ALLOWED`, `PRIVATE_ONLY`
- `ExternalListingType`: `NONE`, `OWN_WEBSITE_SOCIAL`, `AGGREGATOR_PLATFORM`
- `PriceInputMode`: `GROSS`, `NET`
- `TransportMode`: `WALKING`, `PUBLIC_TRANSPORT`, `BICYCLE`, `CAR`, `BOAT`, `MIXED`, `OTHER`

**Availability & booking**
- `AvailabilityStatus`: `AVAILABLE`, `BLOCKED`, `CANCELLED`
- `BookingStatus`: `REQUESTED`, `ACCEPTED`, `PENDING_PAYMENT`, `CONFIRMED`, `DECLINED`, `CANCELLED_BY_LOGGED_IN_USER`, `CANCELLED_BY_LOCAL`, `CANCELLED_BY_ADMIN`, `CANCELLED_MINIMUM_NOT_MET`, `COMPLETED`, `EXPIRED`
- `BookingSource`: `LOGGED_IN_USER`, `GUEST_USER`, `ADMIN`
- `BookingCancellationActor`: `LOGGED_IN_USER`, `LOCAL`, `ADMIN`
- `AttendanceOutcome`: `NONE`, `HOST_NO_SHOW`, `CUSTOMER_NO_SHOW` (admin-verified no-show verdict — distinct from `GuestShowStatus`)
- `GuestShowStatus`: `PENDING`, `SHOWED`, `NO_SHOW` (host's in-person mark per booking; operational, not an admin verdict)
- `CheckInRole`: `HOST`, `GUEST` (geo check-in actor)
- `WaitlistStatus`: `WAITING`, `NOTIFIED`, `CONVERTED`, `CANCELLED`

**Payments / payouts / invoices**
- `PaymentStatus`: `PENDING`, `PROCESSING`, `PAID`, `FAILED`, `CANCELLED`, `REFUNDED`, `PARTIALLY_REFUNDED`, `REFUND_PENDING`, `REFUND_FAILED`
- `PaymentProvider`: `STRIPE`, `PAYPAL`, `ADYEN`, `MANUAL`
- `PaymentMethodType`: `CARD`, `DEBIT_CARD`, `APPLE_PAY`, `GOOGLE_PAY`, `KLARNA`, `PAYPAL`, `BANK_TRANSFER`, `UNKNOWN`
- `PayoutStatus`: `PENDING`, `PROCESSING`, `PAID`, `FAILED`
- `LedgerEntryStatus`: `PENDING`, `AVAILABLE`, `PAID`, `REVERSED`
- `LedgerEntryType`: `EARNING`, `REVERSAL`, `ADJUSTMENT`
- `InvoiceType`: `COMMISSION`, `SERVICE_FEE_RECEIPT`, `PAYOUT_STATEMENT`
- `InvoiceStatus`: `DRAFT`, `ISSUED`, `SENT`, `VOID`
- `RecipientType`: `HOST`, `CUSTOMER`
- `ScopeType` (rates): `PLATFORM`, `CITY`, `CATEGORY`, `HOST`, `EXPERIENCE`
- `CommissionVatTreatment`: `STANDARD`, `NOT_REGISTERED`, `REVERSE_CHARGE`, `OUT_OF_SCOPE`

**Messaging & notifications**
- `ConversationType`: `CUSTOMER_HOST`, `ADMIN_CUSTOMER`, `ADMIN_HOST`
- `ParticipantRole`: `CUSTOMER`, `HOST`, `ADMIN`
- `NoShowSubject`: `HOST`, `CUSTOMER`
- `NoShowReportStatus`: `REQUESTED`, `APPROVED`, `REJECTED`
- `NotificationChannel`: `EMAIL`, `SMS`, `WHATSAPP`, `IN_APP`
- `NotificationStatus`: `PENDING`, `PROCESSING`, `SENT`, `FAILED`, `SKIPPED`
- `NotificationType`: `BOOKING_CREATED`, `BOOKING_ACCEPTED`, `BOOKING_DECLINED`, `BOOKING_CANCELLED`, `BOOKING_COMPLETED`, `BOOKING_UPDATED`, `BOOKING_CONFIRMED`, `BOOKING_REMINDER`, `GUEST_BOOKING_CREATED`, `NEW_MESSAGE`, `INVOICE_ISSUED`, `WAITLIST_SPOT_AVAILABLE`, `SLOT_UNDERBOOKED_HOST_NOTICE`, `SLOT_CANCELLED_MINIMUM_NOT_MET`, `LOCAL_PROFILE_SUBMITTED`, `LOCAL_PROFILE_APPROVED`, `LOCAL_PROFILE_CHANGES_REQUESTED`, `LOCAL_PROFILE_REJECTED`, `SAFETY_REPORT_CREATED`, `SAFETY_REPORT_RESOLVED`, `SYSTEM_ALERT`, `NEWSLETTER_CONFIRM`, `NEWSLETTER`, `HOST_ANNOUNCEMENT`, `PLATFORM_ANNOUNCEMENT`, `WISHLIST_REMINDER`, `BOOKING_ABANDONED_REMINDER`, `HOST_ARRIVED`
- `NewsletterAudience`: `TRAVELER`, `HOST`, `ALL`
- `NewsletterSubscriptionStatus`: `PENDING`, `CONFIRMED`, `UNSUBSCRIBED`
- `AnnouncementAudience`: `MY_FOLLOWERS`, `MY_GUESTS`, `BOTH`, `ALL_HOSTS`

**Promotions**
- `PromoDiscountType`: `PERCENTAGE`, `FIXED_AMOUNT`
- `DealType`: `SPECIAL`, `SEASONAL`, `LAST_MINUTE`, `EARLY_BIRD`, `FLASH`
- `DealScope`: `GLOBAL`, `CITY`, `EXPERIENCE`, `CATEGORY`
- `DealDiscountType`: `PERCENTAGE`, `FIXED_AMOUNT`
- `GiftCardStatus`: `ACTIVE`, `DEPLETED`, `CANCELLED`, `EXPIRED`
- `ReferralRewardStatus`: `PENDING`, `ELIGIBLE`, `PROCESSED`, `CANCELLED`

**Reviews & safety** (two safety subsystems exist — pick by endpoint module)
- `ReviewDirection`: `TRAVELER_TO_HOST`, `HOST_TO_TRAVELER`
- `ReviewStatus`: `VISIBLE`, `HIDDEN`
- `BookingSafetyRoleContext`: `TRAVELER`, `LOCAL`
- `TripSafetyEventType`: `CHECK_IN`, `CHECK_OUT`, `SOS`
- **`safety` package** — `SafetyReportType`: `UNSAFE_BEHAVIOR`, `HARASSMENT`, `NO_SHOW`, `FRAUD`, `PAYMENT_ISSUE`, `INAPPROPRIATE_CONTENT`, `OTHER`; `SafetyReportStatus`: `OPEN`, `IN_REVIEW`, `RESOLVED`, `DISMISSED`; `SafetySeverity`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- **`trustsafety` package** — `SafetyReportType`: `NO_SHOW`, `HARASSMENT`, `UNSAFE_BEHAVIOR`, `FRAUD`, `PAYMENT_ISSUE`, `INAPPROPRIATE_CONDUCT`, `OTHER` (note `INAPPROPRIATE_CONDUCT` vs the other's `INAPPROPRIATE_CONTENT`); `SafetyReportStatus`: `OPEN`, `IN_REVIEW`, `ACTION_REQUIRED`, `RESOLVED`, `DISMISSED`; `SafetyReportSeverity`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`

---

# 4. Endpoint reference (by domain)

_Auth label per endpoint: **Public** (no token) · **Authenticated** (any logged-in user) · **ADMIN** (admin role). Field "required?" reflects validation on the request DTO._

## 4.1 Auth, Account & Users

### `/api/auth` — AuthController

`POST /api/auth/signup` — **Auth:** Public — Register a new user account.
Request (`SignupRequest`): `fullName` (required, ≤150), `email` (required, valid, ≤255), `phone` (optional, ≤30), `password` (required; 8–100 chars, no whitespace, needs upper+lower+digit+special), `role` (`UserRole`, required).
Response (`AuthResponse`): `userId`, `fullName`, `email`, `role`, `status`, `message`. **201** on success; **400** if email already used. **No token returned — call `/login` next.**

`POST /api/auth/login` — **Auth:** Public — Authenticate, receive token.
Request (`LoginRequest`): `email` (required, valid), `password` (required).
Response (`LoginResponse`): `accessToken` (the JWT), `tokenType` ("Bearer"), `userId`, `fullName`, `email`, `role`, `status`. **401** on bad credentials.

`POST /api/auth/social` — **Auth:** Public — Login/signup via social provider.
Request (`SocialLoginRequest`): `provider` (`GOOGLE`|`FACEBOOK`|`APPLE`, required), `token` (required — Google/Apple ID token or Facebook access token; backend verifies it).
Response (`LoginResponse`): same as `/login`. **401** if token unverifiable.

`GET /api/auth/me` — **Auth:** Authenticated — Current user (lean).
Response (`CurrentUserResponse`): `userId`, `fullName`, `email`, `phone`, `role`, `status`, `emailVerified`, `phoneVerified`.

### `/api/account` — AccountController (self-service, any role)

`GET /api/account/me` — **Auth:** Authenticated — My full profile.
Response (`UserResponse`): `id`, `fullName`, `email`, `phone`, `role`, `status`, `emailVerified`, `phoneVerified`, `createdAt`, `updatedAt`.

`PUT /api/account/me` — **Auth:** Authenticated — Update my basic profile.
Request (`UpdateProfileRequest`): `fullName` (required, ≤150), `phone` (optional, ≤30). Only these two are editable. Response: `UserResponse`.

### `/api/users` — UserController (ADMIN)

`POST /api/users` — **Auth:** ADMIN — Create a user. Request (`CreateUserRequest`): `fullName` (req, ≤150), `email` (req, valid, ≤255), `phone` (opt, ≤30), `role` (req). Response **201** `UserResponse` (no password set here).
`GET /api/users` — **Auth:** ADMIN — List users → `UserResponse[]`.
`GET /api/users/{id}` — **Auth:** ADMIN — One user → `UserResponse`; **404** if absent.

### `/api/admin/users` — AdminUserController (ADMIN)

`GET /api/admin/users` — **Auth:** ADMIN — List → `AdminUserResponse[]` (`id`, `email`, `role`, `status`, `createdAt`, `updatedAt` — leaner, no name/phone).
`GET /api/admin/users/{userId}` — **Auth:** ADMIN — One → `AdminUserResponse`.
`PUT /api/admin/users/{userId}/status` — **Auth:** ADMIN — Set account status. Request (`AdminUserStatusRequest`): `status` (`UserStatus`, required), `reason` (optional, ≤1000). Response: `AdminUserResponse`.

### `/api/consents` — ConsentController (Authenticated)

`GET /api/consents/my-status` — Get consent status. Response (`ConsentStatusResponse`): `hasAcceptedRequiredTravelerConsents`, `hasAcceptedRequiredLocalConsents`, `currentVersion`, `acceptedConsentTypes[]`, `missingTravelerConsentTypes[]`, `missingLocalConsentTypes[]`. Use the `missing*` lists to gate traveler/host actions.
`POST /api/consents/accept` — Record one consent. Request (`AcceptConsentRequest`): `consentType` (required), `version` (required, ≤80). Response (`UserConsentResponse`): `id`, `userId`, `consentType`, `version`, `acceptedAt`, `ipAddress`, `userAgent`. (IP + User-Agent captured server-side.)
`POST /api/consents/accept-required-traveler` — Accept all traveler consents (no body) → `UserConsentResponse[]`.
`POST /api/consents/accept-required-local` — Accept all host consents (no body) → `UserConsentResponse[]`.

### `/api/account/gdpr` — AccountGdprController (Authenticated)

`GET /api/account/gdpr/export` — Export all my personal data (`GdprExportResponse`: `exportedAt`, `account`, `consents[]`, `notificationPreferences`, `bookings[]`, `payments[]`, `reviews[]`).
`POST /api/account/gdpr/delete-requests` — Request account deletion/anonymization. Request (`CreateDataDeletionRequest`, optional body): `reason` (optional, ≤2000). Response **201** (`DataDeletionRequestResponse`); starts `REQUESTED`, anonymized later by admin.
`GET /api/account/gdpr/delete-requests` — My deletion requests → `DataDeletionRequestResponse[]`.

### `/api/admin/gdpr` — AdminGdprController (ADMIN)

`GET /api/admin/gdpr/delete-requests?pendingOnly=` — List (filter pending). → `DataDeletionRequestResponse[]`.
`POST /api/admin/gdpr/delete-requests/{requestId}/process` — Approve (⚠️ **anonymizes the user**). Optional `ProcessDataDeletionRequest` body: `adminNote` (≤2000). → status `PROCESSED`.
`POST /api/admin/gdpr/delete-requests/{requestId}/reject` — Reject. Optional `adminNote`. → status `REJECTED`.

## 4.2 Host Profiles, Cities & Categories

**Shared `LocalProfileResponse`** (returned by host/public/admin profile endpoints): `id`, `userId`, `displayName`, `phoneNumber`, `bio`, `profilePhotoUrl`, `hostCity`, `zipCode`, `country`, `experienceLanguages[]`, `experienceCities[]` (`CityResponse`), `experienceCategories[]` (`ExperienceCategoryResponse`), `motivation`, `experienceInfo`, `verificationStatus`, `approvalStatus`, `adminReviewNote`, `rejectionReason`, `changesRequestedReason`, `reviewedAt`, `submittedAt`, `resubmittedAt`, `legalFirstName`, `legalLastName`, `preferredName`, `currentAddress`, `accountNumber`, `accountName`, `swiftCode`, verification metadata, `ratingAvg`, `totalReviews`, `createdAt`, `updatedAt`. ⚠️ The **public** endpoint currently returns this full shape including bank/legal fields — confirm with backend whether to trim before exposing.

### `/api/local-profiles` — LocalProfileController (Authenticated; role LOCAL enforced in service)

`POST /api/local-profiles/me` — Create my host profile. Request (`CreateLocalProfileRequest`): `displayName` (req, ≤150), `phoneNumber` (req, ≤40), `bio` (req, ≤2000), `profilePhotoUrl` (req, ≤2000), `hostCity` (req, ≤100), `zipCode` (req, ≤20), `country` (req, ≤100), `experienceCityIds` (req, non-empty, must be active), `experienceCategoryIds` (req, non-empty, active), `experienceLanguages` (req, non-empty), `motivation` (req, ≤3000), `experienceInfo` (req, ≤5000), `legalFirstName`/`legalLastName`/`preferredName` (req, ≤120), `currentAddress` (req, ≤2000), `accountNumber` (opt, ≤64), `accountName` (opt, ≤150), `swiftCode` (opt, ≤32). Response **201** `LocalProfileResponse`. Only `LOCAL` users; 400 if a profile already exists. Starts `verificationStatus=NOT_STARTED`, `approvalStatus=DRAFT`.
`GET /api/local-profiles/me` — My profile; **404** if none.
`PUT /api/local-profiles/me` — Update (same fields as create). `BLOCKED` can't update; editing an `APPROVED` profile auto-resubmits to `SUBMITTED`; `CHANGES_REQUESTED`/`REJECTED` reset to `DRAFT`.
`POST /api/local-profiles/me/submit` — Submit for review (no body). Only `DRAFT`/`CHANGES_REQUESTED`/`REJECTED`. Sets `SUBMITTED`; emails host.
`GET /api/local-profiles/onboarding-status` — Drive onboarding UI. Response (`LocalOnboardingStatusResponse`): `hasProfile`, `localProfileId`, `approvalStatus`, `verificationStatus`, `canEdit`, `canSubmit`, `canCreateExperience`, `message`.

### `/api/public/local-profiles` — PublicLocalProfileController (Public)

`GET /api/public/local-profiles?city=` — List APPROVED profiles (optional case-insensitive city filter). → `LocalProfileResponse[]`.
`GET /api/public/local-profiles/{profileId}` — One APPROVED profile; **404** if not found/approved.

### `/api/admin/local-profiles` — AdminLocalProfileController (ADMIN)

`GET /api/admin/local-profiles/pending` — Profiles in `SUBMITTED`.
`POST /api/admin/local-profiles/{profileId}/approve` — Approve (only `SUBMITTED`). Emails host; host may now create experiences.
`POST /api/admin/local-profiles/{profileId}/reject` — Request (`AdminLocalProfileReviewRequest`): `adminNote` (opt, ≤2000), `reason` (req, ≤2000). → `REJECTED`; emails host.
`POST /api/admin/local-profiles/{profileId}/request-changes` — Same body. → `CHANGES_REQUESTED`; emails host.

### `/api/local-profiles/me/tax-info` — HostTaxInfoController (Authenticated)

`GET` — My tax info (`HostTaxInfoResponse`: `vatRegistered`, `vatNumber`, `taxCountry`, `legalEntityType`, `taxIdentificationNumber`, `businessRegistrationNumber`, `dateOfBirth`).
`PUT` — Upsert. Request (`HostTaxInfoRequest`, all optional): `vatRegistered` (Boolean), `vatNumber` (≤40), `taxCountry` (≤2), `legalEntityType` (≤20), `taxIdentificationNumber` (≤60), `businessRegistrationNumber` (≤60), `dateOfBirth` (LocalDate).

### `/api/cities` — CityController (Public)
`GET /api/cities` — Active cities for the picker → `CityResponse[]` (`id`, `name`, `slug`, `country`, `active`, `displayOrder`).

### `/api/admin/cities` — AdminCityController (ADMIN)
`GET /api/admin/cities` — All cities (incl. inactive).
`POST /api/admin/cities` — Request (`CreateCityRequest`): `name` (req, ≤100), `country` (req, ≤100), `displayOrder` (opt). **201**; 400 on duplicate.
`POST /api/admin/cities/{cityId}/activate` · `POST /.../deactivate` — toggle selectable.

### `/api/experience-categories` — ExperienceCategoryController (Public)
`GET /api/experience-categories` — Active categories → `ExperienceCategoryResponse[]` (`id`, `name`, `slug`, `description`, `displayOrder`).

## 4.3 Experiences & Photos

**`CreateExperienceRequest`** fields: `categoryId` (UUID, optional on create), `categoryIds` (Set<UUID>, optional), `cityId` (UUID, **required**), `title` (req, ≤150), `description` (req, ≤3000), `meetingArea` (opt, ≤150), `durationMinutes` (req, 30–720), `priceAmount` (BigDecimal ≥0; required server-side for SHARED/PRIVATE_ALLOWED), `currency` (req, exactly 3), `maxGuests` (req, 1–10), `latitude`/`longitude` (BigDecimal, optional meeting-point coords — must be set **together**; lat −90..90, lng −180..180), `safetyNotes` (opt, ≤2000), `shortDescription` (opt, ≤300), `transportMode` (`TransportMode`, opt), `inclusions`/`exclusions` (opt, ≤2000), `endLocation` (opt, ≤255), `reasonsToBook` (opt, ≤2000), `minimumAge` (opt, 0–120), `bookingMode` (`BookingMode`, default SHARED), `privatePrice` (BigDecimal ≥0; required for PRIVATE_ALLOWED/PRIVATE_ONLY), `priceInputMode` (`PriceInputMode`, default GROSS), `externalListingType` (`ExternalListingType`, default NONE), `externalListingDetails` (opt, ≤500).
**`UpdateExperienceRequest`** = same, but `categoryId` is **required**.
**`ExperienceResponse`** fields: `id`, `localProfileId`, `categoryId`, `categoryName`, `categorySlug`, `categoryIds`, `cityId`, `cityName`, `citySlug`, `country`, `title`, `slug`, `description`, `meetingArea`, `durationMinutes`, `priceAmount` (gross/person), `currency`, `maxGuests`, `latitude`, `longitude`, `bookingMode`, `privatePrice`, `priceNetAmount` (computed), `priceInputMode`, `safetyNotes`, `shortDescription`, `transportMode`, `inclusions`, `exclusions`, `endLocation`, `reasonsToBook`, `minimumAge`, `status` (`ExperienceStatus`), `createdAt`, `updatedAt`, `externalListingType`, `externalListingDetails`.
**`ExperiencePageResponse`**: `content[]`, `page`, `size`, `totalElements`, `totalPages`.
Pricing rules to surface as validation are in §2.6.

### `/api/experiences` — ExperienceController (Authenticated; owner)
`POST /api/experiences` — Create (`CreateExperienceRequest`) → **201** `ExperienceResponse`.
`GET /api/experiences/me` — My experiences → `ExperienceResponse[]`.
`GET /api/experiences/me/{experienceId}` — One of mine; **404** if not owned.
`PUT /api/experiences/{experienceId}` — Update (`UpdateExperienceRequest`).
`POST /api/experiences/{experienceId}/submit` — Submit for review.

### `/api/public/experiences` — PublicExperienceController (Public; APPROVED only)
`GET /api/public/experiences?citySlug=&categorySlug=` — List (not paginated).
`GET /api/public/experiences/map?citySlug=&categorySlug=&lat=&lng=&radiusKm=` — **Auth:** Public — lightweight markers for map rendering (only experiences that have coordinates). `ExperienceMapMarker`: `id`, `slug`, `title`, `latitude`, `longitude`, `priceAmount`, `currency`, `cityName`, `distanceKm`. Pass the viewer's `lat`/`lng` (from the browser Geolocation API) to get `distanceKm` per marker + **nearest-first** ordering; add `radiusKm` to restrict to nearby ones. Without lat/lng, returns all city markers (distanceKm null).
`GET /api/public/experiences/search` — Paginated. Query: `citySlug`, `categorySlug`, `date` (yyyy-MM-dd), `adults`, `teens`, `children`, `infants`, `page`(0), `size`(20) → `ExperiencePageResponse`.
`GET /api/public/experiences/search/advanced` — Above plus `minPrice`, `maxPrice`, `maxDurationMinutes`, `minHostRating` (0–5), `keyword`.
`GET /api/public/experiences/{experienceId}` — One by id.
`GET /api/public/experiences/slug/{slug}` — One by slug.

### `/api/admin/experiences` — AdminExperienceController (ADMIN)
`GET /api/admin/experiences/pending` — Awaiting review.
`POST /api/admin/experiences/{experienceId}/approve` — Publish.
`POST /api/admin/experiences/{experienceId}/reject` — Reject.

### `/api/experiences/{experienceId}/photos` — ExperiencePhotoController (Authenticated; host)
**`ExperiencePhotoResponse`**: `id`, `experienceId`, `url`, `caption`, `contentType`, `sizeBytes`, `sortOrder`, `cover`, `createdAt`.
`GET .../photos` — Ordered gallery.
`POST .../photos` — **multipart/form-data**: `file` (required), `caption` (optional) → **201**.
`POST .../photos/external` — Register a URL (`RegisterPhotoUrlRequest`: `url` req ≤2000, `caption` opt ≤300) → **201**.
`DELETE .../photos/{photoId}` — Remove → **204**.
`PUT .../photos/{photoId}/cover` — Set cover.
`PUT .../photos/order` — Reorder (`ReorderPhotosRequest`: `photoIds` non-empty) → reordered list.

### `/api/public/experiences/{experienceId}/photos` — PublicExperiencePhotoController (Public)
`GET` — Ordered gallery (`ExperiencePhotoResponse[]`).

## 4.4 Availability & Bookings

**`BookingResponse`** fields: `id`, `bookingReference` (`LB-…`), `loggedInUserId` (null for guests), `guestName`/`guestEmail`/`guestPhone`, `guestEmailVerified`/`guestPhoneVerified`, `bookingSource`, `localProfileId`, `experienceId`, `availabilitySlotId`, `guestsCount`, `status` (`BookingStatus`), `pricePerGuest`, `totalAmount`, `currency`, `travelerNote`, `localResponseNote`, `cancellationReason`, `requestedAt`/`acceptedAt`/`declinedAt`/`cancelledAt`/`completedAt`/`createdAt`/`updatedAt`, `guestTermsAccepted`/`guestSafetyAccepted`/`guestLiabilityAccepted`, `guestConsentVersion`, `guestConsentAcceptedAt`, `promoCodeId`/`referralCodeId`, `originalAmount`/`discountAmount`, `promoCodeText`/`referralCodeText`, `privateBooking`, `privateDiscountAmount`, `seatsBlocked`.
**`AvailabilitySlotResponse`**: `id`, `experienceId`, `localProfileId`, `startTime`, `endTime`, `capacity`, `bookedCount`, `remainingCapacity`, `privateBookingAvailable`, `status` (`AvailabilityStatus`), `createdAt`, `updatedAt`.
Booking/cancellation rules in §2.6/§2.7.

### `/api/availability` — AvailabilitySlotController (Authenticated; host)
`POST /api/availability` — Create slot (`CreateAvailabilitySlotRequest`: `experienceId` req, `startTime` req future, `endTime` req future, `capacity` req 1–10) → **201**.
`GET /api/availability/me` — My slots.
`POST /api/availability/{slotId}/block` — Block (400 if active bookings).
`POST /api/availability/{slotId}/unblock` — Re-open.
`DELETE /api/availability/{slotId}` — Delete (**204**; 400 if active bookings).

### `/api/public/experiences/{experienceId}/availability` — PublicAvailabilityController (Public)
`GET` — Bookable slots for an experience → `AvailabilitySlotResponse[]`.

### `/api/availability/{slotId}/cancel-underbooked` — UnderbookedSlotController (Authenticated; host)
`POST` — Host cancels an under-booked slot (cancels all active bookings; minimum-participants not met). Optional `CancelBookingRequest` (`reason`). → `UnderbookedSlotCancellationResponse` (`slotId`, `slotStatus`, `cancelledBookings`).

### `/api/bookings` — BookingController (Authenticated)
`POST /api/bookings` — Create as logged-in traveler (`CreateBookingRequest`: `experienceId` req, `availabilitySlotId` req, `guestsCount` req 1–10, `adults`/`teens`/`children`/`infants` ≥0, `travelerNote` ≤1000, `promoCode` ≤80, `referralCode` ≤80, `privateBooking` Boolean) → **201**.
`GET /api/bookings/me` — My bookings.
`POST /api/bookings/{bookingId}/accept` · `/decline` — Host decision (`BookingDecisionRequest`: `note` ≤1000).
`POST /api/bookings/{bookingId}/cancel-by-traveler` — Traveler cancel (`CancelBookingRequest`: `reason` **required** ≤1000). Refund 100% if ≥24h else 0%. → `CANCELLED_BY_LOGGED_IN_USER`.
`POST /api/bookings/{bookingId}/cancel-by-local` — Host cancel (`reason` required). **Rejected within 24h** of start; 100% refund. → `CANCELLED_BY_LOCAL`.
`GET /api/bookings/{bookingId}` — One (must be traveler/host on it, else 404).
`POST /api/bookings/{bookingId}/complete` — Mark COMPLETED.
`POST /api/bookings/{bookingId}/reschedule` — Host moves to new slot (`RescheduleBookingRequest`: `newAvailabilitySlotId` req, `reason` ≤1000).
`POST /api/bookings/checkout` — Book + start Stripe checkout (`CreateBookingRequest`) → **201** `BookingCheckoutResponse` (`{ booking, checkout }`).

### `/api/public/guest-bookings` — PublicGuestBookingController (Public, rate-limited)
`POST /api/public/guest-bookings` — Guest booking (`CreateGuestBookingRequest`: experience/slot/guests + `adults..infants`, `guestName` req ≤150, `guestEmail` req valid ≤255, `guestPhone` req ≤30, `travelerNote` ≤1000, `promoCode`/`referralCode` ≤80, `acceptedTerms` **required**, `consentVersion` **required** ≤50, `privateBooking`) → **201** (`bookingSource=GUEST_USER`).
`POST /api/public/guest-bookings/lookup` — Retrieve by `{ bookingReference (≤40), guestEmail }`.

### `/api/admin/bookings` — AdminBookingController (ADMIN)
`GET /api/admin/bookings?status=` — All bookings (optional status filter).
`POST /api/admin/bookings` — Manual booking-on-behalf (`AdminCreateBookingRequest`: experience/slot, `guestName` req, `guestEmail` req, `guestPhone` opt, guests + bands, `privateBooking`, `note` ≤1000) → **201** (`bookingSource=ADMIN`, confirmed, offline payment).
`GET /api/admin/bookings/{bookingId}` — Any booking.
`POST /api/admin/bookings/{bookingId}/cancel` — Admin cancel (`reason` required), 100% refund → `CANCELLED_BY_ADMIN`.
`POST /api/admin/bookings/{bookingId}/reschedule` — Move to new slot.

### `/api/bookings/{bookingId}/calendar*` — CalendarController (Authenticated; participant)
`GET .../calendar.ics` — Download `.ics` (`text/calendar`).
`GET .../calendar-links` — `CalendarLinksResponse`: `googleLink`, `outlookLink`, `icsPath`.

### `/api/bookings/{bookingId}/wallet` — WalletController (Authenticated; participant)
`GET .../wallet` — `WalletLinksResponse`: `googleConfigured`, `googleSaveUrl`, `appleConfigured`, `applePassPath`.
`GET .../wallet/google` — `{ "saveUrl": "…" }`.
`GET .../wallet/apple.pkpass` — Download signed `.pkpass`.

### `/api/bookings/{bookingId}/safety-checklist` — BookingSafetyChecklistController (Authenticated; participant)
`GET` — My checklist for the booking (`BookingSafetyChecklistResponse`: 4 acknowledgement booleans + `completed`, `completedAt`, `roleContext`, …).
`POST .../complete` — Complete it. Request (`CompleteBookingSafetyChecklistRequest`): all four ack fields are `@AssertTrue` (**must be true**) — `publicMeetingAcknowledged`, `communicationGuidelinesAcknowledged`, `personalSafetyAcknowledged`, `reportingGuidelinesAcknowledged`. (IP/User-Agent captured.)

### Geo check-in & attendance — Booking/Public/Host controllers

> Check-in proves presence near the experience's meeting point (its `latitude`/`longitude`) around start time. **Window:** opens 15 min before start, closes 15 min after (both configurable). **Geofence:** 300 m, configurable, GPS-error-adjusted (`distance − min(accuracy, 500 m) ≤ radius`). All coordinates from the browser Geolocation API; server stamps `checkedInAt`. Distances in **metres**. Operational signal only — **not** auto-refund proof.
>
> **Guests are hard-gated** — a check-in only succeeds inside the geofence, in-window, with a usable accuracy reading, and only if a meeting point is set; otherwise **400** with a human-readable message (too early / too late / too imprecise / "you're about N m away" / no meeting point). **Hosts are soft** — never rejected for distance; the check-in records and the response carries a `warning` + `distanceMeters` when they're outside the fence.

`CheckInResponse`: `role` (`HOST`/`GUEST`), `checkedInAt`, `withinGeofence`, `distanceMeters` (null if no meeting point), `geofenceRadiusMeters`, `warning` (host only; null when inside), `photoUrl` (host only).

**`POST /api/bookings/{bookingId}/check-in`** — Authenticated traveler checks into their own booking. `CheckInRequest`: `latitude` req (−90..90), `longitude` req (−180..180), `accuracyMeters` opt but **required to pass the guest gate** (≥0; rejected if > 500 m). Booking must be `CONFIRMED`. → `CheckInResponse` (success only).

**`POST /api/public/check-in`** — Public, rate-limited. Anonymous guest via `GuestCheckInRequest`: `bookingReference` req (≤40), `guestEmail` req (valid, ≤255), `latitude`/`longitude`/`accuracyMeters` as above. Resolves a `GUEST_USER` booking by reference + email (404 otherwise). Same hard gate. → `CheckInResponse`.

**`POST /api/host/slots/{slotId}/check-in`** — Authenticated host (ownership enforced; else 404). **`multipart/form-data`**: `latitude` req, `longitude` req, `accuracyMeters` opt, `photo` opt (optional live arrival picture, reuses blob storage). Soft check-in. On success notifies every `CONFIRMED` booking's guest **`HOST_ARRIVED`** (in-app + email for logged-in, email for guests; deduped per booking). → `CheckInResponse` (with `warning`/`distanceMeters`/`photoUrl`).

**`GET /api/host/slots/{slotId}/attendance`** — Authenticated host. The arrival roster. `SlotAttendanceResponse`: `slotId`, `experienceTitle`, `startTime`, `meetingPointSet`, `geofenceRadiusMeters`, `hostCheckedIn`, `hostCheckedInAt`, `hostWithinGeofence`, `hostDistanceMeters`, `hostPhotoUrl`, `bookings[]`. Each `BookingAttendanceRow`: `bookingId`, `bookingReference`, `guestDisplayName`, `guestPhone`, `partySize`, `guestShowStatus` (`GuestShowStatus`), `guestCheckedIn`, `guestCheckedInAt`, `guestDistanceMeters`, `guestWithinGeofence`. Use it to know who's checked in nearby (whom to call) before marking show/no-show.

**`POST /api/host/bookings/{bookingId}/attendance`** — Authenticated host marks one booking's guest(s) as shown/not. `MarkAttendanceRequest`: `status` = `SHOWED` or `NO_SHOW` (`PENDING` rejected). One mark per booking regardless of party size. Booking must be `CONFIRMED`/`COMPLETED`. → updated `BookingAttendanceRow`. (This is the host's operational mark — separate from the admin-verified `AttendanceOutcome` no-show verdict.)

## 4.5 Payments, Payouts & Invoices

Flow in §2.3/§2.4. Two parallel paths share DTOs/statuses: logged-in (`/api/payments`, by booking UUID) and guest (`/api/public/guest-payments`, by reference+email, rate-limited). The Stripe webhook is **server-to-server — never called by the frontend**; re-fetch the payment after the user returns from Stripe.
**`PaymentResponse`**: `id`, `bookingId`, `provider`, `paymentMethodType`, `paymentStatus`, `amount`, `currency`, `platformFeeAmount`, `localPayoutAmount`, `providerCheckoutSessionId`, `providerPaymentIntentId`, `checkoutUrl`, `paidAt`, `failedAt`, `cancelledAt`, `refundedAt`, `createdAt`, `updatedAt`.
**`PaymentCheckoutResponse`**: `paymentId`, `bookingId`, `provider`, `paymentMethodType`, `paymentStatus`, `amount`, `currency`, `checkoutUrl` (redirect to Stripe).

### `/api/payments` — PaymentController (Authenticated)
`POST /api/payments` — Create pending payment (`CreatePaymentRequest`: `bookingId` req) → **201** (no checkout URL).
`GET /api/payments/{paymentId}` — Fetch (poll status after Stripe).
`POST /api/payments/checkout` — Create Stripe session (`bookingId` req) → **201** `PaymentCheckoutResponse`.

### `/api/public/guest-payments` — PublicGuestPaymentController (Public, rate-limited)
`POST /api/public/guest-payments` — Pending guest payment (`CreateGuestPaymentRequest`: `bookingReference` req ≤40, `guestEmail` req valid ≤255).
`POST /api/public/guest-payments/lookup` — Status (`GuestPaymentLookupRequest`: same).
`POST /api/public/guest-payments/checkout` — Stripe session for a guest booking.

### `/api/public/payments/webhooks/stripe` — StripeWebhookController (Public; Stripe only)
`POST` — Stripe → backend; verified via `Stripe-Signature`. Drives payment status to `PAID`/`FAILED`/refund states. Frontend never calls this.

### `/api/admin/payments` — AdminPaymentController (ADMIN)
`GET /api/admin/payments?status=` — All payments. `GET /api/admin/payments/{paymentId}` — One.

### `/api/host/payouts` — HostPayoutController (Authenticated; host)
`GET /api/host/payouts/earnings` — `HostEarningsResponse`: `totalEarned`, `totalPaidOut`, `availableBalance`, `onHoldBalance`, `currency`.
`GET /api/host/payouts` — `PayoutResponse[]` (`id`, `localProfileId`, `amount`, `currency`, `status` (`PayoutStatus`), `providerTransferId`, `notes`, `createdAt`, `paidAt`).
`POST /api/host/payouts/connect-onboarding` — Stripe Connect onboarding (`ConnectOnboardingResponse`: `connectAccountId`, `onboardingUrl`).

### `/api/admin/payouts` — AdminPayoutController (ADMIN)
`GET /api/admin/payouts` — All payouts.
`POST /api/admin/payouts/hosts/{localProfileId}` — Aggregate + transfer a host's earnings → **201** `PayoutResponse`.
`POST /api/admin/payouts/{payoutId}/mark-paid?notes=` — Mark paid (manual).

### `/api/invoices` — InvoiceController (Authenticated)
`GET /api/invoices/me` — My invoices (`InvoiceResponse`: `id`, `invoiceNumber`, `invoiceType`, `status`, `recipientType`, `recipientName`, `currency`, `subtotalAmount`, `vatAmount`, `totalAmount`, `vatNote`, `bookingId`, `payoutId`, `issuedAt`).
`GET /api/invoices/{invoiceId}/pdf` — Download PDF.

### `/api/admin/invoices` — AdminInvoiceController (ADMIN)
`GET /api/admin/invoices` — All. `GET /api/admin/invoices/{invoiceId}/pdf` — PDF.

### `/api/admin/company-settings` — AdminCompanySettingsController (ADMIN)
`GET` / `PUT` issuer legal/VAT details on invoices. `UpsertCompanySettingsRequest`: `legalName` (req ≤200) + optional `tradingName`, `vatNumber`, `cocNumber`, `addressLine1/2`, `postalCode`, `city`, `country` (≤2), `email`, `phone`, `iban`, `invoiceNumberPrefix`, `invoiceFooter`.

### `/api/admin/rates` — AdminRatesController (ADMIN)
Commission / service-fee / VAT rules + audit. Each rule has `scopeType` (`ScopeType`), optional `scopeId`, `rate`, `effectiveFrom/To`, `active`, `note`. VAT `rate` is a fraction 0–1; commission/service-fee `rate` ≥0.
`GET|POST /api/admin/rates/commission`, `POST .../commission/{id}/deactivate`; same for `/service-fee`; `GET|POST /api/admin/rates/vat` (`CreateVatRateRequest`: `country` req, `categoryId` opt, `rate` 0–1, `rateKind`, `description`, dates), `.../vat/{id}/deactivate`; `GET /api/admin/rates/audit?rateType=`.

### `/api/admin/cancellation-refund-policies` — AdminCancellationRefundPolicyController (ADMIN)
Refund tiers by `(cancelledBy, hoursBeforeStart)`. `UpsertCancellationRefundPolicyRequest`: `name` (req ≤150), `cancelledBy` (`BookingCancellationActor`), `minHoursBeforeStart` (≥0), `maxHoursBeforeStart` (opt), `refundPercentage` (0–100), `active`.
`GET` list · `POST` create (**201**) · `PUT /{policyId}` update · `POST /{policyId}/deactivate`.

## 4.6 Messaging, No-show, Notifications & Reliability

### `/api/conversations` — ConversationController (Authenticated; participant)
**`ConversationResponse`**: `id`, `type` (`ConversationType`), `experienceId`, `bookingId`, `subject`, `participants[]` (`{userId, name, role}`), `lastMessageAt`, `unreadCount`, `createdAt`.
**`MessageResponse`**: `id`, `conversationId`, `senderUserId`, `senderName`, `senderRole` (`ParticipantRole`), **`senderLabel`** (render verbatim — `"Admin (Name)"` for admins, else the name), `body`, `createdAt`.
`POST /api/conversations` — Start/reuse a thread with an experience's host (`StartConversationRequest`: `experienceId` req). Idempotent → **201**.
`GET /api/conversations` — My conversations (newest first, with `unreadCount`).
`GET /api/conversations/{conversationId}/messages` — Messages (must be participant).
`POST /api/conversations/{conversationId}/messages` — Send (`SendMessageRequest`: `body` req ≤5000) → **201**.
`POST /api/conversations/{conversationId}/read` — Mark read → **204**.

### `/api/admin/conversations` — AdminConversationController (ADMIN)
`GET /api/admin/conversations` — Monitor all threads.
`GET /api/admin/conversations/{conversationId}/messages` — Read any thread.
`POST /api/admin/conversations/{conversationId}/messages` — Reply / take over (admin joins; message labelled `Admin (Name)`, visible to all) → **201**.
`POST /api/admin/conversations/side` — Private side conversation (`AdminStartSideConversationRequest`: `targetUserId` req, `experienceId` opt, `subject` opt ≤200, `body` opt ≤5000 first message) → **201** `ConversationResponse` (type `ADMIN_CUSTOMER`/`ADMIN_HOST`).

### `/api/no-show` — NoShowController (Authenticated)
**`NoShowReportResponse`**: `id`, `bookingId`, `bookingReference`, `reportedByUserId`, `subject` (`NoShowSubject`), `status` (`NoShowReportStatus`), `reason`, `adminNote`, `createdAt`, `resolvedAt`.
`POST /api/no-show/bookings/{bookingId}/report` — Report (within 48h of start). Optional `CreateNoShowReportRequest`: `reason` (≤2000) → **201**. Customer→host = refund claim; host→customer = informational.
`GET /api/no-show/me` — My reports.

### `/api/admin/no-show` — AdminNoShowController (ADMIN)
`GET /api/admin/no-show/reports?pendingOnly=` — List.
`POST /api/admin/no-show/reports/{reportId}/approve` — Approve (**host no-show → full refund**). Optional `adminNote`.
`POST /api/admin/no-show/reports/{reportId}/reject` — Reject. Optional `adminNote`.
`DELETE /api/admin/no-show/bookings/{bookingId}/flag` — Remove flag (does not reverse refund) → **204**.

### `/api/public/guest-no-show` — PublicGuestNoShowController (Public, rate-limited)
`POST /api/public/guest-no-show/report` — Guest reports host no-show (`GuestNoShowReportRequest`: `bookingReference` req ≤40, `guestEmail` req valid ≤255, `reason` opt ≤2000) → **201**; **429** rate limit.

### `/api/notifications` — NotificationController (Authenticated)
**`NotificationResponse`**: `id`, `notificationType`, `subject`, `message`, `relatedEntityType`, `relatedEntityId`, `read`, `readAt`, `createdAt`.
`GET /api/notifications/me` — In-app feed.
`POST /api/notifications/{notificationId}/read` — Mark one read → updated notification.
`POST /api/notifications/read-all` — Mark all → **204**.

### `/api/notifications/preferences` — NotificationPreferenceController (Authenticated)
`GET` — `NotificationPreferenceResponse`: `bookingReminders`, `marketingEmails`, `emailEnabled`, `smsEnabled` (defaults true/false/true/false).
`PUT` — Full update; all four flags `@NotNull` and required every time (no PATCH). Note: only email + SMS toggles exposed (no WhatsApp/in-app toggle).

### `/api/admin/reliability` — AdminReliabilityController (ADMIN)
**`ReliabilitySummaryResponse`**: `scope` ("host"/"experience"/"customer"), `id`, `cancellations`, `noShows`, `completedBookings`. No-shows counted once per slot.
`GET /api/admin/reliability/hosts/{localProfileId}` · `/experiences/{experienceId}` · `/users/{userId}`.

## 4.7 Reviews & Safety

> Two independent safety subsystems exist (`safety` and `trustsafety` packages) with different enum values — use the one matching the endpoint.

### `/api/reviews` — ReviewController (Authenticated)
**`ReviewResponse`**: `id`, `bookingId`, `direction`, `reviewerUserId`, `revieweeUserId`, `localProfileId`, `experienceId`, `rating`, `comment`, `status`, `createdAt`, `updatedAt`.
`POST /api/reviews` — Traveler→host review (`CreateReviewRequest`: `bookingId` req, `rating` req 1–5, `comment` opt ≤2000). Requires caller = booking's traveler, booking **COMPLETED**, one per booking. Recomputes host rating. → **201**.
`POST /api/reviews/as-host` — Host→traveler review (same body). Guest bookings can't be reviewed.
`GET /api/reviews/me` — Reviews I authored (incl. hidden).
`GET /api/reviews/about-me` — VISIBLE reviews about me.

### `/api/public` — PublicReviewController (Public; TRAVELER_TO_HOST + VISIBLE only)
`GET /api/public/local-profiles/{localProfileId}/reviews`
`GET /api/public/experiences/{experienceId}/reviews`

### `/api/admin/reviews` — AdminReviewController (ADMIN)
`GET /api/admin/reviews` — All (both directions/statuses).
`POST /api/admin/reviews/{reviewId}/hide` — Hide (`AdminReviewModerationRequest`: `reason` opt ≤1000); recomputes rating.
`POST /api/admin/reviews/{reviewId}/unhide` — Restore to VISIBLE.

### `/api/safety/reports` — SafetyReportController (Authenticated; `safety` package)
`POST /api/safety/reports` — File (`CreateSafetyReportRequest`: `reportedUserId` opt, `bookingId` opt, `reportType` req (`SafetyReportType` safety variant), `severity` req (`SafetySeverity`), `description` req ≤3000) → **201** `SafetyReportResponse` (`…`, `status`, `adminNotes`, `resolutionNote`, …).
`GET /api/safety/reports/me` — My reports.

### `/api/admin/safety/reports` — AdminSafetyReportController (ADMIN)
`GET /api/admin/safety/reports?status=` — List.
`POST .../{reportId}/mark-in-review` · `/resolve` · `/dismiss` — (`AdminSafetyReportDecisionRequest`: `adminNotes`, `resolutionNote`, both opt ≤2000).

### `/api/trip-safety` — TripSafetyController (Authenticated)
**`TripSafetyEventResponse`**: `id`, `bookingId`, `eventType` (`CHECK_IN`/`CHECK_OUT`/`SOS`), `latitude`, `longitude`, `note`, `resolved`, `resolvedAt`, `createdAt`.
`GET /api/trip-safety/emergency-contact` — `EmergencyContactResponse` (`contactName`, `contactPhone`, `relationship`) or **204** if none.
`PUT /api/trip-safety/emergency-contact` — Upsert (`UpsertEmergencyContactRequest`: `contactName` req ≤150, `contactPhone` req ≤40, `relationship` opt ≤80).
`POST /api/trip-safety/bookings/{bookingId}/check-in` · `/check-out` — (optional `TripCheckRequest`: `latitude`, `longitude`, `note`). Traveler-only.
`POST /api/trip-safety/bookings/{bookingId}/sos` — Raise SOS + email support (optional `SosRequest`: `latitude`, `longitude`, `message`). Traveler-only.
`GET /api/trip-safety/bookings/{bookingId}/events` — Events for a booking (traveler or host).

### `/api/admin/trip-safety` — AdminTripSafetyController (ADMIN)
`GET /api/admin/trip-safety/sos` — Open SOS events.
`POST /api/admin/trip-safety/sos/{eventId}/resolve` — Resolve (400 if not an SOS event).

### `/api/trust-safety` — TrustSafetyController (Authenticated; `trustsafety` package)
`POST /api/trust-safety/reports` — File booking-scoped report (`CreateSafetyReportRequest` trustsafety variant: `bookingId` **required**, `reportType` req (`INAPPROPRIATE_CONDUCT` variant), `severity` req, `description` req ≤5000). `reportedUserId` auto-derived from the booking. → **201**.

### `/api/admin/trust-safety` — AdminTrustSafetyController (ADMIN)
`GET /api/admin/trust-safety/reports?status=` — List.
`PUT /api/admin/trust-safety/reports/{reportId}` — Update (`UpdateSafetyReportRequest`: `status`, `severity`, `adminNotes` — all optional; RESOLVED/DISMISSED stamps `resolvedAt`).
`GET /api/admin/trust-safety/restrictions` — Active user restrictions (`UserRestrictionResponse`).
`POST /api/admin/trust-safety/restrictions` — Create (`CreateUserRestrictionRequest`: `userId` req, `restrictionType` req (`UserRestrictionType`), `reason` req ≤3000). Enforced in booking/hosting flows.
`POST /api/admin/trust-safety/restrictions/{restrictionId}/deactivate`.

## 4.8 Wishlist, Waitlist, Promos, Referrals, Deals & Gift Cards

### `/api/wishlist` — WishlistController (Authenticated — guests get 401; see §2.5)
**`WishlistItemResponse`**: `experienceId`, `title`, `slug`, `cityName`, `priceAmount`, `currency`, `status`, `addedAt`.
`GET /api/wishlist` — Full wishlist (newest first).
`GET /api/wishlist/ids` — Just the saved experience ids (render hearts in one call).
`POST /api/wishlist/{experienceId}` — Add one (idempotent; experience must be APPROVED else 404) → **201**.
`DELETE /api/wishlist/{experienceId}` — Remove (idempotent) → **204**.
`GET /api/wishlist/{experienceId}/status` — `{ "inWishlist": boolean }`.
`POST /api/wishlist/batch` — Merge guest favourites after login (`BatchWishlistRequest`: `experienceIds` non-empty, max 100). Idempotent; skips unknown/non-approved/already-saved → returns full merged wishlist.

### `/api/waitlist` — WaitlistController (Authenticated)
**`WaitlistResponse`**: `id`, `availabilitySlotId`, `experienceId`, `userId`, `guestEmail`, `guestsCount`, `status` (`WaitlistStatus`), `createdAt`, `notifiedAt`.
`POST /api/waitlist/slots/{slotId}` — Join (`JoinWaitlistRequest`: `guestsCount` 1–10). 400 if slot past/cancelled/has free seats; 400 if already on it.
`GET /api/waitlist/me` — My entries.
`DELETE /api/waitlist/{entryId}` — Leave → **204**.
(Notified ≠ reserved — first-come-first-served; see §2.10.)

### `/api/public/waitlist` — PublicWaitlistController (Public)
`POST /api/public/waitlist/slots/{slotId}` — Guest join (`JoinGuestWaitlistRequest`: `guestsCount` 1–10, `guestName` req ≤150, `guestEmail` req valid ≤255, `guestPhone` req ≤40).

### `/api/promo-codes` — PromoCodeController (Authenticated)
`POST /api/promo-codes/validate` — Validate (`ValidatePromoCodeRequest`: `code` req ≤80, `bookingAmount` req ≥0.01, `currency` req ≤10, `guestEmail` opt). Response (`ValidatePromoCodeResponse`): `valid`, `promoCodeId`, `code`, `discountType`, `discountValue`, `discountAmount`, `finalAmount`, `message`. Invalid codes return **200** with `valid:false` + message. Per-user limit enforced by user id.

### `/api/public/promo-codes` — PublicPromoCodeController (Public)
`POST /api/public/promo-codes/validate` — Same; per-user limit checked against `guestEmail`.

### `/api/admin/promo-codes` — AdminPromoCodeController (ADMIN)
`POST` create (`CreatePromoCodeRequest`: `code` req ≤80, `description` opt, `discountType` req, `discountValue` req ≥0.01, `currency` opt, `maxDiscountAmount`/`minBookingAmount` opt, `maxTotalRedemptions`/`maxRedemptionsPerUser` opt, `startsAt`/`expiresAt` opt, `active` opt). `GET` list · `GET /{promoCodeId}`.
**`PromoCodeResponse`**: …code/discount fields…, `currentRedemptions`, `startsAt`, `expiresAt`, `active`, timestamps.

### `/api/referrals` — ReferralController (Authenticated)
`POST /api/referrals/my-code` — Get/create my code. `GET /api/referrals/my-code` — Get (404 if none).
`POST /api/referrals/validate` — (`ValidateReferralCodeRequest`: `code` req ≤80, `guestEmail` opt). Response: `valid`, `referralCodeId`, `ownerUserId`, `code`, `message`. Can't use own code / can't reuse.
**`ReferralCodeResponse`**: `id`, `ownerUserId`, `code`, `active`, `maxRedemptions` (null=unlimited), `currentRedemptions`, timestamps.

### `/api/public/referrals` — PublicReferralController (Public)
`POST /api/public/referrals/validate` — Same; reuse checked against `guestEmail`.

### `/api/public/deals` — PublicDealController (Public)
`GET /api/public/deals?dealType=&cityId=&experienceId=&categoryId=` — Live deals (active + in window), priority order. **`DealResponse`**: `id`, `name`, `description`, `dealType`, `discountType`, `discountValue`, `currency`, `scope`, `targetCityId`, `targetExperienceId`, `targetCategoryId`, `startsAt`, `endsAt`, `active`, `priority`, `badgeText`, timestamps.

### `/api/admin/deals` — AdminDealController (ADMIN)
`POST` create (`CreateDealRequest`: `name` req ≤200, `dealType` req, `discountType` req, `discountValue` req ≥0, `scope` req, target ids opt, dates opt, `priority` opt, `badgeText` opt ≤80, `active` opt). `GET` list · `GET /{dealId}` · `PUT /{dealId}` (full replace) · `POST /{dealId}/deactivate`.

### `/api/gift-cards` — GiftCardController (Authenticated)
`POST /api/gift-cards/purchase` — (`PurchaseGiftCardRequest`: `amount` req ≥1.00, `currency` opt (default EUR), `recipientEmail` opt, `recipientName` opt, `message` opt). Code `LB-XXXX-XXXX-XXXX`, 365-day expiry, status ACTIVE → **201**.
`GET /api/gift-cards/me` — Cards I purchased.
`POST /api/gift-cards/redeem` — (`RedeemGiftCardRequest`: `code` req, `amount` req ≥0.01, `bookingId` opt). 400 if not ACTIVE / over balance; hits 0 → DEPLETED.
**`GiftCardResponse`**: `id`, `code`, `initialAmount`, `balance`, `currency`, `status`, `recipientEmail`, `recipientName`, `message`, `expiresAt`, `createdAt`.

### `/api/public/gift-cards` — PublicGiftCardController (Public)
`GET /api/public/gift-cards/{code}/balance` — `GiftCardBalanceResponse`: `code`, `balance`, `currency`, `status`, `expiresAt`.

### `/api/admin/gift-cards` — AdminGiftCardController (ADMIN)
`GET /api/admin/gift-cards` — All. `POST /api/admin/gift-cards/{giftCardId}/cancel` — Cancel.

## 4.9 Misc, AI, WhatsApp & Admin Tools

### `/api/public/currency` — PublicCurrencyController (Public)
`GET /api/public/currency/convert?amount=&from=&to=` — `ConversionResponse`: `from`, `to`, `amount`, `convertedAmount`, `rate`.
`GET /api/public/currency/rates?base=EUR` — `RatesResponse`: `base`, `rates` (map code→rate).

### `/api/public/contact-us` — PublicContactUsController (Public, rate-limited)
`POST /api/public/contact-us` — (`ContactUsRequest`: `name` req ≤150, `email` req valid ≤255, `subject` req ≤200, `message` req ≤5000) → `ContactUsResponse` (`message`). **429** when rate-limited.

### `/api/ai` — AiController (Authenticated)
`GET /api/ai/status` — `{ "configured": boolean }` (gate AI UI when not configured).
`POST /api/ai/listing-assistant` — (`ListingAssistantRequest`: `title` req ≤150, `city`/`category` opt ≤120, `highlights` opt ≤2000) → `{ shortDescription, detailedDescription }`.
`POST /api/ai/itinerary` — (`ItineraryRequest`: `city` req ≤120, `interests` opt ≤1000, `durationHours` opt, `partySize` opt) → `{ itinerary }`.
`POST /api/ai/moderation` — (`ModerationRequest`: `text` req ≤5000) → `{ flagged, reason }`.

### `/api/whatsapp` — WhatsAppController (Authenticated)
`GET /api/whatsapp/click-to-chat?phone=&message=` — `{ link }` (wa.me URL).
`GET /api/whatsapp/bookings/{bookingId}/contact` — `BookingContactLinkResponse`: `counterpartRole`, `link` (message the other party). (No inbound WhatsApp webhook exists.)

### `/api/admin/whatsapp` — AdminWhatsAppController (ADMIN)
`GET /api/admin/whatsapp/status` — `{ "configured": boolean }`.
`POST /api/admin/whatsapp/send` — (`SendWhatsAppRequest`: `toPhone` req ≤40, `message` req ≤4000) → `WhatsAppSendResult` (`providerMessageId`, `status`).

### `/api/admin/dashboard` — AdminDashboardController (ADMIN)
`GET /api/admin/dashboard/summary` — `AdminDashboardSummaryResponse` (all `long`): `totalUsers`, `totalLocalProfiles`, `pendingLocalProfiles`, `approvedLocalProfiles`, `totalExperiences`, `pendingExperiences`, `approvedExperiences`, `totalBookings`, `requestedBookings`, `acceptedBookings`, `cancelledBookings`, `openSafetyReports`, `inReviewSafetyReports`, `visibleReviews`, `hiddenReviews`.

### `/api/admin/ops` — AdminOpsController (ADMIN)
`POST /api/admin/ops/local-profiles` — Create/approve a host profile on behalf of a user (`AdminCreateLocalProfileRequest`: `email` req, `displayName` req, rest optional incl. KYC + banking + `adminNote`) → **201** `LocalProfileResponse`.
`POST /api/admin/ops/experiences` — Create an immediately-approved experience (`AdminCreateExperienceRequest`: `localProfileId` req, `cityId` req, `title` req, `description` req, `durationMinutes` req ≥30, `priceAmount` req, `currency` req, `maxGuests` req, …) → **201** `ExperienceResponse`.

### `/api` — HealthController (Public)
`GET /api/health` — `{ status: "UP", service, timestamp }`.

## 4.10 Newsletter, Announcements & Reminders

> New enums: `NewsletterAudience` (`TRAVELER`/`HOST`/`ALL`), `NewsletterSubscriptionStatus` (`PENDING`/`CONFIRMED`/`UNSUBSCRIBED`), `AnnouncementAudience` (`MY_FOLLOWERS`/`MY_GUESTS`/`BOTH`/`ALL_HOSTS`). New `NotificationType` values: `NEWSLETTER_CONFIRM`, `NEWSLETTER`, `HOST_ANNOUNCEMENT`, `PLATFORM_ANNOUNCEMENT`, `WISHLIST_REMINDER`, `BOOKING_ABANDONED_REMINDER` — these surface in the existing notification feed / emails; no new delivery endpoints.

### Newsletter — `/api/public/newsletter` (Public, rate-limited) + `/api/newsletter` (Authenticated) + `/api/admin/newsletter` (ADMIN)
Email-keyed subscriptions with **double opt-in** and **one-click tokenized unsubscribe**. `NewsletterSubscriptionResponse`: `id`, `email`, `audience`, `status`, `confirmedAt`, `createdAt`.
- `POST /api/public/newsletter/subscribe` — **Auth:** Public — anonymous subscribe (`SubscribeNewsletterRequest`: `email` req, `audience` opt (default ALL), `source` opt). Sends a confirmation email; status `PENDING` until confirmed.
- `POST /api/public/newsletter/confirm?token=` — **Auth:** Public — confirm the subscription (double opt-in). → `CONFIRMED`.
- `POST /api/public/newsletter/unsubscribe?token=` — **Auth:** Public — one-click unsubscribe → **204**.
- `POST /api/newsletter/subscribe` — **Auth:** Authenticated — subscribe the current user (segmented by role; auto-confirmed if their email is verified).
- `GET /api/newsletter/me` — **Auth:** Authenticated — my subscription (or **204** if none).
- `GET /api/admin/newsletter/subscriptions` — **Auth:** ADMIN — list all subscriptions.
- `POST /api/admin/newsletter/broadcast` — **Auth:** ADMIN — send to CONFIRMED subscribers in an audience (`NewsletterBroadcastRequest`: `audience` req, `subject` req, `body` req). Returns `{ recipients: <n> }`. Body gets an unsubscribe link appended automatically.

**Frontend note:** a "subscribe" box must work logged-out (public endpoint). After subscribing, show "check your email to confirm." Build `/newsletter/confirm` and `/newsletter/unsubscribe` pages that read `?token=` and call the matching public endpoint.

### Announcements & Follow
**`AnnouncementResponse`**: `id`, `localProfileId` (null for platform), `audience`, `subject`, `body`, `recipientCount`, `createdAt`. Delivered to recipients as in-app + email (email respects the recipient's email preference); guests who booked get email.
- `POST /api/host/announcements` — **Auth:** Authenticated (approved host) — post an announcement (`CreateAnnouncementRequest`: `audience` `MY_FOLLOWERS`/`MY_GUESTS`/`BOTH`, `subject` req ≤200, `body` req ≤5000) → **201**.
- `GET /api/host/announcements` — **Auth:** Authenticated (host) — my announcements.
- `POST /api/admin/announcements/platform` — **Auth:** ADMIN — announce to all hosts (`PlatformAnnouncementRequest`: `subject`, `body`) → **201**.
- `GET /api/admin/announcements` — **Auth:** ADMIN — all announcements.
- `POST /api/follows/{localProfileId}` — **Auth:** Authenticated — follow a host → **204** (idempotent).
- `DELETE /api/follows/{localProfileId}` — **Auth:** Authenticated — unfollow → **204**.
- `GET /api/follows/me` — **Auth:** Authenticated — hosts I follow (`FollowedHostResponse`: `localProfileId`, `displayName`, `followedAt`).

### Reminders (no endpoints — scheduled)
Wishlist-not-booked and abandoned-booking (EXPIRED, unpaid) reminders fire automatically at configurable offsets (default **2h / 24h / 48h**, `app.reminders.offsets-hours`), once per stage, as `WISHLIST_REMINDER` / `BOOKING_ABANDONED_REMINDER` notifications (in-app + email). The abandoned-booking nudge links back to the experience to re-book. Nothing for the frontend to call — these appear in the notification feed / inbox.

---

## 5. Notes for the frontend developer (breaking changes & gotchas)

- **`externalListingType`** on experiences replaced an older `listedOnExternalPlatform` boolean — it's a 3-value enum now (`NONE`/`OWN_WEBSITE_SOCIAL`/`AGGREGATOR_PLATFORM`); only `AGGREGATOR_PLATFORM` blocks private booking.
- **Messaging** is participant-based: a conversation has a `participants[]` array and a `type`; messages carry `senderRole` + `senderLabel`. Render admin authorship from `senderLabel` ("Admin (Name)"), never reconstruct it. There is no per-message "read" flag — unread is tracked per participant and surfaced as `unreadCount` on the conversation.
- **No refresh token** yet — handle `401` by re-authenticating.
- **Guest identity = bookingReference + email** across booking, payment, and no-show lookups.
- **Public profile response** currently includes host bank/legal fields — treat as sensitive; confirm trimming with backend.
- **Two safety-report systems** (`/api/safety/reports` vs `/api/trust-safety/reports`) with slightly different enums; pick per feature.
- Regenerate your typed client from `/v3/api-docs` after each backend deploy — that's the guarantee that nothing new is missed.

---

## 6. UI handover — landing & experience pages (visual prototype)

> **Status: front-end design prototype, not wired to the API.** Two self-contained pages in `landing/` show the intended look, layout, components and responsive behaviour. All data shown is placeholder/demo. Use these as the implementation reference; bind real data via the API sections above. A zipped copy is at `localbuddy-landing.zip` (repo root).

### 6.0 Files & how to run
- `landing/index.html` — home / marketplace landing page.
- `landing/experience.html` — experience-detail page (example: *Hidden Canals by Bike*).
- Pure HTML + CSS + vanilla JS, **all inlined** (no build step, no framework). Each file is standalone and links to the other relatively — keep them in the same folder.
- Open directly (double-click) or serve: `python3 -m http.server 8000 -d landing` → http://localhost:8000/index.html.
- **External runtime deps:** Google Fonts (Inter) + Unsplash photos (each `<img>` has an `onerror` fallback to picsum, so nothing renders broken). Icons are **inline SVG** (see §6.3) — no icon CDN.

### 6.1 Design system
Five-colour system, flat (no gradients), depth via soft shadows.

| Token | Value | Use |
|---|---|---|
| `--paper` | `#FFFFFF` | page background |
| `--cream` | `#F5F5F7` | alt section fill |
| `--ink` / `--ink2` / `--ink3` | `#111114` / `#6E6E73` / `#86868B` | text / secondary / tertiary |
| `--navy` | `#0C1320` | dark sections (host band, footer, "Three steps", trust band, map panel) + "on-yellow" text |
| `--terra` / `--terra2` | `#D62F2A` / `#C0241F` | **red accent** — buttons, links, prices, active states, hover |
| `--yellow` | `#FFDE5D` | header background + sparse accents (verified pill, trust medallions, hero kicker text, one highlight) |
| subheader | `#0B0C10` | black secondary nav bar |
| `--line` | `#E6E6E9` | hairlines/borders |
| stars (`--amber`) | `#17171A` | rating stars are near-black, not gold |

- **Type:** Inter only (weights 400–800); `--serif` is aliased to Inter (no serif anywhere). Section headings are **navy** (`.h2`, `.sec-h`, `.pg-h`).
- **Radius:** `--r:18px` general; **home-page experience cards and city tiles are sharp (radius 0)** by request. Most other elements rounded.
- **Shadows:** `--shadow-sm/md/lg` for elevation; no gradient fills (hero/city image scrims are flat `rgba` overlays).

### 6.2 Global chrome (both pages)
- **Header (`#nav`)** — yellow `#FFDE5D`, sticky `top:0`, height 64px. Logo "LocalBuddy" (navy, red dot), nav links + Sign-in (navy) hidden ≤900px behind a hamburger → slide-down `.mmenu`. *Everything on the yellow bar is navy.*
- **Black subheader (`.subnav`)** — non-sticky bar under the header: a yellow 📍 **Amsterdam** city marker + scrollable quick links (*All experiences · Food · Hidden Gems · Café Hopping · Nightlife · Local Markets · Gift cards · How it works · Help centre · Contact*). Horizontally scrolls on mobile. **Wire the city to GET /api/cities and the categories to GET /api/categories — do not hardcode.**
- **Footer** — dark navy, brand + link columns + socials.
- **Icons (`.ic`)** — see §6.3.

### 6.3 Icons
No icon font/CDN. Each page embeds one hidden `<svg><defs>…<symbol id="…">` **sprite** (~48 Tabler-style stroke icons, 24×24, `currentColor`). Usage: `<svg class="ic"><use href="#clock"></use></svg>`, sized at `1em`, colour via `currentColor`. Filled variants (star, verified rosette, map-pin) bake `fill`. If you port to a component framework, swap for your own icon set — the class hooks (`.ic`) and colours are inherited.

### 6.4 Home page (`index.html`) — sections, top → bottom
1. **Hero** — full-bleed Amsterdam photo + flat dark scrim; **dark-glass kicker pill with yellow text**; headline *"Meet the locals. See the city <span red>they</span> love."*; lead; **search bar** (rounded-rect) with fields **WHERE** (city) · **WHEN** (dates) · **WHO** (Shared or Private) + red search button; trust row.
2. **Categories rail** — the 8 real categories (Food, Photo Walk, Hidden Gems, Local Markets, Cafe Hopping, Student Life, Nightlife, Custom). Heading "Find your kind of Amsterdam".
3. **Trending** — experience cards. **Mobile = swipe carousel** (cards left-aligned under the heading, next card peeks, translucent-glass **directional arrows**: first→right only, middle→both, last→left only, + position dots + one-time "Swipe" hint). Cards are sharp rectangles.
4. **Meet the locals** — host grid (term is "locals", never "buddies").
5. **Modes** — *"One experience, two ways to book it"*: **Shared** (per person, 1–10 guests) and **Private buyout** (flat whole-slot). **No "Solo".**
6. **Three steps to a real local day** — dark-navy section, white cards.
7. **Trust** — header "Booked with confidence" + "Real locals. Real reviews. Real safety." on white; the **3 trust signals are a full-width edge-to-edge dark band** (yellow labels + yellow medallions w/ navy icons + white descriptions); testimonials below on white.
8. **Cities** — "Amsterdam now. Europe next." — Amsterdam live + unnamed "Coming soon" tiles (don't name unannounced cities).
9. **Host band** (dark CTA) → **Footer**.

> The previous **"Free to join" section was deleted** — there is no free/tip product (see §6.6).

### 6.5 Experience page (`experience.html`)
- **Gallery** — **desktop:** 4-tile collage (tall photo left, two squares, darkened landscape bottom-right = "Show all 18 photos"). **Mobile:** swipe through **all 18** photos with a persistent **"n / 18" counter** + glass directional arrows. Both open a **lightbox** (role=dialog, 18-thumb strip, ‹/›, Esc, arrow keys). Photo count is illustrative — bind to the experience's real photo list.
- **Title block** — title, Share/Save; meta line `★ ratingAvg · N reviews · ` **yellow "Verified local" pill** ` · area`; quick-fact pills (duration, max guests, an inclusion, host languages, meeting area).
- **Host stripe / "Meet your host"** — "ID-verified, admin-approved", `ratingAvg (totalReviews)`, spoken languages, city. *No response-rate / "responds within 1h" / per-host experience-count — those aren't real fields.*
- **About** (description) · **Good to know** (minimumAge, transportMode, maxGuests, safetyNotes) · **What's included** (inclusions ✓ / exclusions ✕).
- **Reviews** — full-width: aggregate `ratingAvg` + distribution bars + cards (rating + comment + first name + date; **no booking-mode tag** — reviews carry none, and the public payload has **no author name/avatar**, so a real build needs a separate lookup).
- **Map** — dark-navy panel: white "MEETING POINT", **yellow free-text area** ("Jordaan, Amsterdam West"), white note ("exact point after you book"). Use `meetingArea` + optional lat/lng; there is no structured street address.
- **More experiences with this host** — rail.
- **Booking widget** — tabs **Shared** (`€price / person` × guests 1–10) / **Private** (`€privatePrice / slot`, flat, guest counter hidden); date + guest stepper; line items + **Subtotal** with note *"A service fee is added at checkout"* (no hard-coded fee — see §6.6); Reserve; trust (secure prepaid · **full refund 24h before** · reviews-after-trip). **Hide the Private tab when the experience is AGGREGATOR_PLATFORM or while a shared seat exists** (per §3/§4.4).
- **Mobile sticky reserve bar** — price + Reserve; **auto-hides when the footer scrolls into view** (IntersectionObserver).

### 6.6 Backend accuracy — DO NOT re-invent
Earlier drafts of these pages invented features; the current pages were corrected against the backend. Keep them honest:
- **Booking = SHARED / PRIVATE_ALLOWED / PRIVATE_ONLY only — there is no "Solo".** A guest sends a boolean `privateBooking` + guest count (1–10). Shared = per-person; Private buyout = flat whole-slot price (not per-person), blocks full capacity; Private hidden for AGGREGATOR_PLATFORM and once any shared seat is booked.
- **No free / tip / pay-what-you-want experiences.** Every experience has a price and routes through Stripe.
- **Cancellation = 100% refund if ≥24h before start, else 0%** (reason required). Never "48h" (that's the unrelated no-show window).
- **Service fee is real but admin-configurable (default 2.5%) with no public quote endpoint** — do not display a hard-coded "5%"/"€3"; show "added at checkout" or fetch the real breakdown.
- **Hosts are "locals"/"hosts", never "buddies"** (buddy = brand only). Verification copy = "ID-verified, admin-approved" (KYC + approval), **not** "background checked". No response-rate / response-time fields.
- **Ratings live on the host** (`ratingAvg` 0–5, `totalReviews`), not on the experience. Reviews are gated to COMPLETED bookings; public payload = `reviewerUserId + createdAt + rating + comment + direction` (no name/avatar, no mode tag).
- **Categories are DB-seeded + admin-editable** (current: Food, Photo Walk, Hidden Gems, Local Markets, Cafe Hopping, Student Life, Nightlife, Custom) — load from the API.
- **Real experience fields:** title, description, shortDescription, durationMinutes, maxGuests, inclusions, exclusions, reasonsToBook, free-text meetingArea (+ endLocation), lat/lng, minimumAge, safetyNotes, transportMode. **No itinerary table, no structured address, no per-experience languages** (languages live on the host profile).
- **Only Amsterdam is live**; render the city list from GET /api/cities.

### 6.7 API wiring map (UI element → section above)
| UI | Bind to |
|---|---|
| Subheader city / hero WHERE | §4.2 Cities (`GET /api/cities`) |
| Category rail / subheader categories | §4.2 Categories |
| Hero search, trending & "more" cards | §4.3 Experiences & Photos |
| Booking widget (Shared/Private, date, guests, remaining seats) | §4.4 Availability & Bookings (`slot.capacity − bookedCount`, `privateBooking`) |
| Price / fee / Reserve → checkout | §4.5 Payments (Stripe; fee not publicly itemised) |
| Reviews block + aggregate | §4.7 Reviews (`ratingAvg`/`totalReviews` on host; gated to COMPLETED) |
| Host stripe / "Meet your host" | §4.2 Host Profiles (trim sensitive bank/legal fields) |
| Heart / Save | §4.8 Wishlist |
| Gift cards / promo / referral inputs | §4.8 |
| Meeting point reveal post-booking | §4.4 booking + §4.6 messaging |
| Map pin | experience lat/lng (§4.3) |

### 6.8 Placeholders to replace
- All `images.unsplash.com` / `picsum.photos` URLs → real `experience.photos`, `host.photo`, city imagery.
- Demo numbers (ratings, review counts, "312", "€45/€240") → API values (`ratingAvg`, `totalReviews`, `priceAmount`, `privatePrice`).
- Hardcoded category/city names in the rails & subheader → `GET /api/categories`, `GET /api/cities`.
- Gallery's fixed 18 photos → the experience's actual photo set (variable length).

### 6.9 Accessibility & performance notes
- Inline SVG sprite (no icon-font FOIT). `prefers-reduced-motion` disables transitions/reveals.
- Hero/first images use `fetchpriority="high"`/eager; the rest are `loading="lazy"`.
- `:focus-visible` outlines; icon-only buttons have `aria-label`; lightbox is `role="dialog"` with keyboard nav; carousels are `scroll-snap` (work without JS, JS adds dots/arrows/counter).
- `body{overflow-x:clip}` prevents horizontal scrollbars from full-bleed sections — keep it if you port the CSS.

