# LocalBuddy — Backend Capabilities & Feature Overview
*Business overview · June 2026*

LocalBuddy is a curated marketplace that connects travellers with verified local hosts running small, personal experiences in Amsterdam — “a trusted layer between what the guidebooks say and what a local friend would actually show you.” This document is a business-level overview of everything the backend can do today. It was produced by auditing the live codebase (538 source files across ~45 domains) against the API reference, so it reflects shipped functionality, not roadmap.

## Executive summary

LocalBuddy’s backend is a complete, production-grade two-sided marketplace — not a thin booking layer. It already spans the full lifecycle for both travellers and local hosts, with 192 distinct, code-backed features across 55 functional areas, grouped into the seven pillars below.

- **Trust & verification, built in.** Hosts are ID/KYC-verified and admin-approved before they can list; two-way reviews feed public ratings; and there are multiple, independent safety systems (incident reports, live trip-safety/SOS, no-show handling, and geofenced arrival check-in).
- **A real financial engine.** Not just ‘take a payment’ — a full money stack: Stripe hosted checkout, a pricing engine that splits guest service fee from host commission with VAT and configurable ‘cost-bearer’ logic, Stripe Connect payouts, automated refunds with clawback, and VAT invoices/receipts/payout statements.
- **Conversion & growth tooling.** Gift cards, promo codes & targeted vouchers (stackable), referral codes, admin-run deals, a waitlist for full slots, wishlists, a double-opt-in newsletter, and automated reminders (upcoming bookings, abandoned-booking recovery, wishlist nudges).
- **Guest-first, low-friction.** Travellers can browse, book and pay without ever creating an account; guest identity is carried by a booking reference + email across booking, payment, check-in and no-show flows. Social login (Google/Facebook) speeds up those who do sign up.
- **Local-host operations.** Self-service onboarding, profile & bank/tax capture, availability management, an earnings ledger & payout dashboard, host announcements with a follower base, and an AI listing-copy assistant.
- **Modern, mobile-ready extras.** Claude-powered AI (availability answers, listing help, itinerary suggestions, content moderation), WhatsApp click-to-chat, Apple/Google Wallet event passes, and add-to-calendar.
- **A deep admin & operations console.** Review queues for host applications, experiences and reviews; safety-report triage; user, booking, payment and city management; configurable commission/fee/refund rules with an audit trail; platform metrics; and rate-limiting.

In short, the platform is materially more complete than a typical early-stage booking site: the differentiated, trust-heavy, guest-friendly, financially-rigorous foundations are already in place. The main product opportunity flagged during the audit is that there is currently no ‘free / community experience’ product — every experience is priced and routes through Stripe — so supporting RSVP-able or info-only free listings would be a net-new backend capability to plan for.

## The platform at a glance

1. **Accounts, identity, consent & data rights** — 19 features  
   _Sign-up & login · Identity, sessions & access control · Account & profile management · Admin user management · Consent management (versioned) · GDPR data rights · Audit logging_
2. **Hosts, cities, categories, experiences & media** — 27 features  
   _Host (Local) Onboarding & Profile · Host Approval, Verification & Moderation · Public Host Profiles · Cities / Markets · Categories · Experience Listings · Public Experience Discovery & Search · Photos & Media · Multi-currency Display_
3. **Availability, bookings & pricing** — 27 features  
   _Availability slots & capacity · Booking creation & channels · Shared vs private-buyout booking · Booking lifecycle & states · Pricing engine_
4. **Payments, payouts, invoicing & growth money** — 31 features  
   _Checkout & Payments (Stripe) · Refunds & Cancellation Policy · Host Earnings Ledger & Payouts · Commission, Service Fee & VAT Pricing Engine · Invoicing & Receipts · Gift Cards · Promo Codes & Vouchers · Referrals · Admin Deals & Discounts_
5. **Reviews, safety, trust & attendance** — 27 features  
   _Reviews & rating aggregation · User safety reporting · Account restrictions & enforcement · Trip safety, SOS & emergency contact (live experience) · No-show reporting & refund handling · Geo check-in & arrival attendance · Booking safety checklist_
6. **Messaging, notifications, comms & engagement** — 30 features  
   _In-app messaging & conversations · Notifications (multi-channel delivery) · Notification preferences · Automated reminders · Contact & support · Host & platform announcements + following · Newsletter · Wishlist / favourites · Waitlist_
7. **AI, integrations, admin & platform ops** — 31 features  
   _AI Assistant (Claude-powered) · WhatsApp integration · Mobile wallet passes · Add-to-calendar · Admin dashboard & metrics · Admin moderation & review queues · Admin operations (provisioning) · Rate limiting · Cross-cutting platform & config_

---

## Feature catalogue

## 1. Accounts, identity, consent & data rights

### Sign-up & login

**Email & password sign-up** — New visitors can self-register with full name, email, optional phone, password and a chosen role. The system rejects duplicate emails and silently normalises emails to lowercase. The account is created in a 'pending verification' state with email and phone marked unverified.
*Business value:* Lets travellers and prospective hosts create their own accounts without staff involvement, the entry point to the whole marketplace.
- Only the guest (LOGGED_IN_USER) and host (LOCAL) roles can be chosen at public sign-up; attempts to self-register as ADMIN or SUPPORT are rejected.
- Passwords must be 8-100 characters with at least one uppercase, one lowercase, one digit and one special character, and may contain no whitespace.
- Passwords are stored only as BCrypt hashes, never in plain text.
- New accounts start in PENDING_VERIFICATION status with emailVerified=false and phoneVerified=false.

**Email & password login** — Registered users log in with email and password and receive an access token. Wrong email or wrong password both return the same generic 'invalid email or password' message.
*Business value:* Standard returning-user access; the uniform error message avoids revealing which emails are registered.
- Login is blocked for accounts whose status is SUSPENDED or DELETED ('Account is not active').
- Accounts created via social login have no password and therefore cannot log in through the password endpoint.

**Social login (Google, Facebook, Apple)** — Users can sign in by passing a token from a social identity provider. The backend verifies the token directly with the provider, then finds or auto-creates a matching LocalBuddy account keyed on the verified email address.
*Business value:* Lower-friction onboarding/login and reuse of trusted identity providers; reduces password management.
- Google and Facebook are fully implemented; Apple is declared as a provider but has no verifier wired in, so Apple sign-in currently returns 'Sign-in with APPLE is not available yet'.
- Google tokens are validated against Google's tokeninfo endpoint, optionally checked against a configured client ID, and rejected unless the Google email is verified.
- Facebook identities are read from the Graph API and rejected if no email is shared.
- If no account exists for the verified email, one is auto-created as a guest (LOGGED_IN_USER), set ACTIVE, with email pre-marked verified and no password.
- Social login is also blocked for SUSPENDED or DELETED accounts.

### Identity, sessions & access control

**Stateless JWT access tokens** — On successful login the backend issues a signed, time-limited JWT 'Bearer' access token carrying the user's id, email and role. Every protected request is authenticated from this token; there are no server-side sessions.
*Business value:* Scalable, stateless authentication that works across web and mobile clients.
- Token lifetime is configurable (access-token-expiration-minutes); signed with an HMAC secret from configuration.
- No refresh-token mechanism exists; when the access token expires the user must log in again.
- On each request the token is re-validated and the user re-loaded from the database, so a deleted/missing user is effectively rejected; an invalid/expired token simply leaves the request unauthenticated.

**Role-based permissions** — Every account has exactly one of four roles - guest (LOGGED_IN_USER), local host (LOCAL), ADMIN, or SUPPORT - which drives what endpoints it can reach.
*Business value:* Separates traveller, host and staff capabilities and protects administrative functions.
- The user's role is embedded as a Spring Security authority (ROLE_*) on each request.
- Public, unauthenticated routes include health checks, API docs/Swagger, all /api/auth endpoints, /api/public, experience categories and cities.
- /api/users/** and /api/admin/** are restricted to ADMIN; all other endpoints require any authenticated user.
- SUPPORT is a defined role but is not granted any distinct route access in this cluster's security rules.

**Cross-origin (CORS) access policy** — The API only accepts browser requests from a configured allow-list of front-end origins, with credentials enabled and a fixed set of HTTP methods.
*Business value:* Lets the official web front-end call the API while blocking unauthorised cross-site browser access.
- Allowed origins come from configuration (default http://localhost:3000); allowed methods are GET/POST/PUT/PATCH/DELETE/OPTIONS.
- CSRF protection, form login and HTTP Basic are disabled in favour of token auth.

### Account & profile management

**View current user / my profile** — Authenticated users can fetch their own account details, including name, email, phone, role, status and whether email/phone are verified.
*Business value:* Lets the app show and confirm who is logged in and the verification state of their account.
- Exposed both as /api/auth/me (identity summary) and /api/account/me (fuller profile including timestamps).

**Self-service profile editing** — Logged-in users can update their own basic profile - full name and phone number. Clearing the phone field removes it.
*Business value:* Keeps personal contact details current without staff intervention.
- Only full name and phone are editable here; email and role cannot be changed via self-service.
- Lives under /api/account so it is available to any authenticated role, separate from the admin-only /api/users area.

**Verification flags & rating fields** — Each account tracks email-verified and phone-verified booleans, plus an aggregate rating average and total-reviews count surfaced on the user record.
*Business value:* Underpins trust signals (verified status, reputation) across the marketplace.
- Verification flags are stored and reported but no verification-issuing flow exists in this cluster (social login is the only path that auto-sets email-verified).
- ratingAvg and totalReviews are maintained on the user but populated by the reviews subsystem, not here.

### Admin user management

**Admin user directory** — Admins can list all registered users and look up any single user, seeing administrative details such as email, role, status and created/updated timestamps.
*Business value:* Gives operations staff oversight of the whole user base for support and moderation.
- Two overlapping admin surfaces exist: /api/users (create + list + get, full profile) and /api/admin/users (list + get + status change, admin-detail view).

**Admin user creation** — Admins can create user accounts directly (name, email, phone, role), including staff roles. Created accounts start pending verification and without a password.
*Business value:* Lets staff provision accounts (e.g. internal/staff users) outside the public sign-up path.
- Unlike public sign-up, admin creation accepts any role including ADMIN/SUPPORT and sets no password (the account cannot password-login until one is set elsewhere).
- Duplicate emails are rejected.

**Account status control (activate / suspend)** — Admins can change a user's account status, used to suspend or re-activate accounts.
*Business value:* Enforcement lever for moderation, fraud and policy violations - suspended users cannot log in.
- Status can only be set to ACTIVE or SUSPENDED via this API; other targets are rejected.
- ADMIN-role users cannot have their status changed through this API.
- Already-DELETED users cannot be modified.
- The four account states are ACTIVE, PENDING_VERIFICATION, SUSPENDED, DELETED.

### Consent management (versioned)

**Versioned consent acceptance** — The system records a user's acceptance of specific legal consents tied to an explicit document version, capturing when it was accepted along with the user's IP address and browser/user-agent.
*Business value:* Provides an auditable, version-aware legal record that a user agreed to specific terms - important for GDPR and dispute defence.
- Consent types: Terms of Service, Privacy Policy, Community Guidelines, Safety Guidelines, Liability Acknowledgement.
- Current consent version is a fixed constant in code ('2026-05-v1'); there is no admin UI to publish new versions.
- Each (user, consent type, version) is unique; re-accepting the same version is idempotent and returns the existing record.
- IP is resolved from X-Forwarded-For / X-Real-IP / remote address; IP and user-agent are stored with each acceptance.

**Role-specific required-consent bundles** — The platform defines which consents are mandatory to act as a traveller versus as a local host, and offers one-click endpoints to accept all required consents for each role at the current version.
*Business value:* Streamlines onboarding gating - users can accept the exact set of legal docs needed for their intended activity in a single step.
- Required for travellers: Terms of Service + Privacy Policy.
- Required for hosts (locals): Terms of Service + Privacy Policy + Community Guidelines.
- Bulk-accept endpoints exist for each role (accept-required-traveler / accept-required-local).

**Consent status check & enforcement gate** — Users can query their current consent status to see which required consents (per role) are still missing at the current version. Internally other services can require traveller/host consents before allowing an action.
*Business value:* Lets the app prompt for outstanding agreements and lets the backend block actions until mandatory consent is given.
- Status response flags whether traveller-required and host-required consents are all satisfied, lists accepted types at the current version, and lists what is missing for each role.
- Re-accepting is required whenever the current version constant changes, since only acceptances matching the current version count.
- Enforcement hooks (requireTravelerConsents / requireLocalConsents) throw a validation error listing the missing consents if called when consents are incomplete.

### GDPR data rights

**Self-service personal data export** — Authenticated users can download a portable export of their personal data, assembled across the platform: account profile, consent history, notification preferences, bookings, payments, reviews and event check-ins.
*Business value:* Satisfies the GDPR right of access/portability and lets users see everything held about them.
- Export includes account details, every consent record, notification preferences, booking history (reference, status, experience, slot time, guests, amount, currency), payments (status, amount, refunded amount, paid date), reviews (direction, rating, comment, status), and geo check-ins (time, distance, within-geofence flag).
- Returned synchronously as a JSON payload stamped with an export timestamp.

**Account deletion / anonymisation request** — Users can request deletion of their account, optionally giving a reason. The request is queued for admin review rather than executed immediately, and users can list their own past requests.
*Business value:* Satisfies the GDPR right to erasure while keeping a human-reviewed, controlled deletion process.
- Only one pending (REQUESTED) deletion request is allowed per user at a time.
- Requests move through states REQUESTED -> PROCESSED or REJECTED.

**Admin processing of deletion requests** — Admins can list deletion requests (optionally only pending ones), then approve a request - which anonymises the account - or reject it, in both cases recording an optional admin note and a processed timestamp.
*Business value:* Gives compliance staff controlled, auditable handling of erasure requests.
- Approval anonymises in place: name becomes 'Deleted User', email is replaced with a unique deleted+<id>@deleted.localbuddy.invalid address, phone and password are cleared, verification flags reset, and status set to DELETED.
- Anonymisation preserves the user row (and thus linked bookings/payments history) rather than hard-deleting records.
- Only requests still in REQUESTED state can be processed or rejected.

### Audit logging

**Consent acceptance audit trail** — There is no general-purpose audit-logging subsystem in this cluster (the audit package is an empty placeholder). The de-facto audit trail for identity/legal events is the consent records and deletion-request records, which capture who, what, when, plus IP/user-agent for consents and admin notes/timestamps for deletions.
*Business value:* Provides the evidentiary trail needed for GDPR/legal disputes around consent and erasure, even without a dedicated audit log.
- The com.localbuddy.audit package contains only a .gitkeep - no audit entity, service or log table here.
- Consent records persist IP address, user-agent and accepted-at timestamps as legal evidence.
- Deletion requests persist requester, reason, admin note, status and processed-at timestamp.
- A separate rate-change audit exists but belongs to the pricing subsystem, not accounts/identity.

## 2. Hosts, cities, categories, experiences & media

### Host (Local) Onboarding & Profile

**Self-service host profile creation & editing** — A user with the LOCAL role can create one host (local) profile capturing display name, bio, phone, host city/zip/country, profile photo, spoken/experience languages, the cities and categories they want to operate in, motivation and experience-info free-text, and legal identity fields (legal first/last name, preferred name, current address). They can view and update it via /api/local-profiles/me.
*Business value:* Lets locals present themselves to travellers and supplies the data the marketplace and admins need to vet and list them.
- Exactly one profile per user (enforced); only LOCAL-role users may create one.
- Selected cities and categories must reference existing, active records or the request is rejected.
- At least one experience language is required; cities/categories de-duplicated on save.
- Hosts are NOT restricted to approved/operating cities for their own host city (free text); only experience-city selections must be active.

**Onboarding status tracker** — A dedicated endpoint reports whether the user has a profile yet, its approval and verification status, and computed flags for whether they can currently edit, submit, or create experiences, plus a plain-language next-step message.
*Business value:* Drives the host onboarding UI so locals always know what to do next.
- canEdit when status is DRAFT, CHANGES_REQUESTED, REJECTED or APPROVED.
- canSubmit when DRAFT, CHANGES_REQUESTED or REJECTED.
- canCreateExperience only when APPROVED.
- Returns a 'please create your profile' state when no profile exists.

**Submission & resubmission workflow** — A host submits the profile for admin review; on submit the system clears prior review notes and records first-submission and resubmission timestamps. Editing an already-approved profile automatically reverts it to a re-review (SUBMITTED) state; editing a rejected/changes-requested profile returns it to DRAFT.
*Business value:* Ensures any material change to a live host is re-vetted while keeping a clean audit of when things were submitted.
- Only DRAFT, CHANGES_REQUESTED or REJECTED profiles can be submitted.
- BLOCKED profiles cannot be updated, submitted, approved, rejected or sent for changes.
- Submission triggers a host email notification.

**Host bank/payout & tax identity capture** — The profile optionally stores payout bank details (account number, account name, SWIFT) and a separate Host Tax Info area captures VAT-registered flag, VAT number, tax-residence country (ISO-2), legal entity type, DAC7 tax identification number, business registration number and date of birth.
*Business value:* Supports payouts and EU VAT/DAC7 tax compliance for hosts running paid experiences.
- Tax info is editable any time via /api/local-profiles/me/tax-info (separate from the main profile).
- Tax/VAT fields are intentionally optional at onboarding and can be completed later.
- Profile also holds Stripe Connect account id, payouts-enabled flag and an optional per-host commission-rate override (used by pricing/payout logic).

### Host Approval, Verification & Moderation

**Admin review queue & decisions** — Admins can list pending (submitted) host profiles and approve them, reject them with a required reason, or request changes with a reason — each decision stamps a reviewed-at time and notifies the host by email.
*Business value:* Gatekeeps the 'curated, verified locals' promise — only vetted hosts go live and can publish experiences.
- Approval is only possible from the SUBMITTED state.
- Rejection requires a reason; request-changes requires a reason; both accept an optional internal admin note.
- Approval clears prior rejection/changes reasons and unlocks experience creation.
- Each outcome (submitted/approved/rejected/changes-requested) sends a distinct host email.

**Approval lifecycle states** — Every host profile carries an approval status: DRAFT, SUBMITTED, CHANGES_REQUESTED, APPROVED, REJECTED, or BLOCKED, which governs what the host and admins can do.
*Business value:* Provides a clear, auditable host lifecycle from application through live status, suspension, or removal.
- BLOCKED is a terminal/locked state that disables all host and admin edit actions on the profile.

**ID / KYC verification status (data model + manual approval)** — Each profile tracks an identity-verification status (NOT_STARTED, ID_PENDING, ID_VERIFIED, MANUALLY_APPROVED, REJECTED) plus verification-provider metadata fields (provider, reference id, started/completed timestamps, failure reason).
*Business value:* Records identity-verification state for trust/safety and is surfaced to the host onboarding UI.
- No automated KYC/ID-verification provider integration is wired up in this cluster's code: status is set to NOT_STARTED on creation and only to MANUALLY_APPROVED via an admin-ops action.
- Provider metadata fields exist on the entity/response but are not populated by any verification flow here.
- A separate legacy VerificationStatus enum (NOT_STARTED/PENDING/PASSED/FAILED/MANUAL_REVIEW) exists but is unused by the active flow.

**Admin-ops fast-track host onboarding** — An internal admin-ops action can create-or-update a host profile by user email and immediately set it APPROVED and verification MANUALLY_APPROVED, bypassing the normal submit/review cycle.
*Business value:* Lets operators onboard trusted/seed hosts directly without the full self-service flow.
- Requires the target user to already exist with the LOCAL role.

### Public Host Profiles

**Public host directory & profile pages** — Unauthenticated visitors can list approved host profiles (optionally filtered by host city) and fetch a single approved host profile by id.
*Business value:* Powers public host browsing and the 'meet your local' profile pages that build traveller trust.
- Only APPROVED profiles are returned; any non-approved id returns not-found.
- Profile responses include host rating average and total review count for social proof.

### Cities / Markets

**City catalogue (public selector)** — A public endpoint lists active cities (name, slug, country, display order) used to populate city pickers for hosts and travellers.
*Business value:* Defines the markets LocalBuddy operates in and feeds every city filter and selector.
- Active cities are ordered by display order then name.

**Admin city management** — Admins can list all cities (including inactive), add a new city (auto-slugged, duplicate-name protected, with a display order), and activate or deactivate a city.
*Business value:* Lets operators open or close markets without code changes.
- Deactivating a city removes it from new-experience selectors but leaves existing experiences unaffected.
- City names are unique (case-insensitive); slugs are auto-generated and de-duplicated.

### Categories

**Experience category catalogue (public selector)** — A public endpoint lists active experience categories (name, slug, description, display order) used to tag experiences and power category filters.
*Business value:* Organises the catalogue so travellers can browse experiences by theme/interest.
- Categories are read-only via the API in this cluster (no admin create/update/deactivate controller); they are seeded/maintained via database migrations.
- Each category has an active flag and display order; only active categories are listed.

### Experience Listings

**Experience creation & rich listing fields** — An approved host can create experiences with a title, auto-generated unique slug, long and short descriptions, primary category plus additional categories, city, meeting area and end location, transport mode, inclusions/exclusions, reasons-to-book, duration, minimum age, max guests, optional meeting-point coordinates, and safety notes.
*Business value:* Captures everything needed to merchandise a personal local experience to travellers.
- Only APPROVED hosts can create experiences; host must also pass trust/safety and consent checks.
- Duration constrained 30–720 minutes; max guests 1–10 (MVP cap); title <=150, description <=3000 chars.
- Latitude and longitude must be supplied together (or both omitted).
- Supports a primary category plus a many-to-many set of additional categories; all category/city selections must be active.

**Booking modes (shared vs private buyout)** — Each experience declares how it can be booked: SHARED (guests join up to capacity), PRIVATE_ALLOWED (can be booked shared or as a whole-slot private buyout), or PRIVATE_ONLY (every booking reserves the whole slot at a flat private price).
*Business value:* Enables both join-a-group pricing and lucrative private/whole-group buyouts, expanding monetisation options.
- Non-shared modes require a private price; PRIVATE_ONLY needs only a flat private price, others also need a per-person price.
- For PRIVATE_ALLOWED the flat private price may not exceed maxGuests × per-person price.
- Default mode is SHARED.

**External-listing declaration** — When creating an experience the host declares whether it is also offered elsewhere: NONE, OWN_WEBSITE_SOCIAL, or AGGREGATOR_PLATFORM (Airbnb/Viator/GetYourGuide, etc.), with optional details text.
*Business value:* Protects slot-exclusivity guarantees and informs operations about channel conflicts.
- Aggregator-platform listings are blocked from offering private buyouts (only shared bookings allowed) because slot exclusivity can't be guaranteed; own-website/social and not-listed are unaffected.

**VAT-aware pricing (gross/net) & currency** — Hosts enter a price as either gross (customer-facing, VAT-inclusive) or net (before VAT); the system stores both, deriving the counterpart from the experience's VAT rate, and records a 3-letter currency (default EUR) plus optional VAT-category override and per-experience commission override.
*Business value:* Lets hosts price intuitively while keeping VAT-correct gross/net figures for compliant invoicing and payouts.
- Price input mode GROSS or NET; net/gross computed from the resolved VAT rate at save time.
- Optional per-experience commission rate supersedes the host's rate.
- Currency stored uppercased; price scaled to 2 decimals.

**Experience review & publishing lifecycle** — Experiences move through DRAFT, SUBMITTED, APPROVED, REJECTED, PAUSED, and BLOCKED. Hosts submit for review; admins list pending and approve/reject. Editing an approved experience reverts it to SUBMITTED for re-review.
*Business value:* Keeps the public catalogue curated and ensures changes to live listings are re-checked.
- Only the owning host can view/edit/submit their experience; BLOCKED experiences can't be edited/submitted/approved/rejected.
- Admin experience endpoints expose list-pending, approve and reject; PAUSED/BLOCKED exist as statuses (set elsewhere, e.g. trust/safety), not via the admin-experience approve/reject controller.
- Updating an approved experience auto-resubmits it (status back to SUBMITTED).

**Host experience management (My experiences)** — Hosts can list all their experiences and fetch any single one they own by id, regardless of status.
*Business value:* Gives hosts a dashboard to manage their full portfolio of listings.
- Ownership is enforced; another host's experience id returns not-found.

### Public Experience Discovery & Search

**Browse & fetch approved experiences** — Unauthenticated visitors can list approved experiences (optionally filtered by city slug and/or category slug) and fetch a single approved experience by id or by URL slug.
*Business value:* Core traveller-facing catalogue browsing and SEO-friendly experience detail pages.
- Only APPROVED experiences are exposed; non-approved ids/slugs return not-found.

**Filtered search with party composition & availability** — A paginated search filters approved experiences by city, category, desired date, and party makeup (adults, teens, children, infants), excluding experiences whose minimum age would bar the youngest traveller and matching against capacity and (when a date is given) available future slots.
*Business value:* Returns only experiences a specific group can actually book on a chosen day.
- Minimum-age gating derived from the youngest band present (infant=0, child<=2, teen<=13).
- Total party size is matched against capacity; date narrows to that day's slots.
- Page size capped at 100, default 20.

**Advanced search** — Extends search with price range (min/max per guest), maximum duration, minimum host rating, and a free-text keyword matched against title and description, on top of the standard city/category/date/party filters.
*Business value:* Lets travellers narrow by budget, time, quality and keywords for more relevant results.
- All advanced filters are optional and combine with the base filters; paginated.

**Map markers with 'near me' distance & radius** — Returns lightweight map markers for approved experiences that have coordinates; if the viewer supplies their lat/lng, each marker gets a distance in km and results are sorted nearest-first, optionally restricted to a radius.
*Business value:* Powers a map view and proximity-based discovery ('experiences near me').
- Distance computed via the Haversine great-circle formula, rounded to 0.01 km.
- Experiences without coordinates are omitted from the map; city/category filters still apply.
- Markers include slug, title, coordinates, price, currency and city name.

### Photos & Media

**Experience photo gallery management** — Hosts manage an ordered photo gallery per experience: upload an image file (stored in blob storage), or register an externally-hosted image URL, with an optional caption; delete photos; set a cover image; and reorder the gallery.
*Business value:* Gives each listing a compelling, host-curated visual gallery — central to converting browsers to bookers.
- Maximum 20 photos per experience; empty uploads rejected.
- The first photo added is auto-set as cover; deleting the cover promotes the next photo to cover.
- Reorder accepts a list of photo ids and appends any unreferenced photos preserving order.
- Only the owning host can modify a gallery (ownership enforced).

**Public photo gallery** — Anyone can fetch an experience's ordered photo gallery (cover first by sort order) without authentication.
*Business value:* Serves listing images to the public traveller-facing experience pages.

**Azure Blob storage backend with graceful fallback** — Uploaded images are stored in Azure Blob Storage under per-experience keys with the correct content type, returning a public URL; the provider initialises lazily so the app runs without blob config in dev/test.
*Business value:* Scalable, CDN-friendly image hosting that degrades gracefully when not configured.
- When blob storage is unconfigured, direct file upload returns a clear error directing hosts to the external-URL endpoint.
- File extension inferred from filename or content type (jpg/png/webp/gif); delete is best-effort and never fails the request.
- Records storage key, URL, content type, byte size, caption, sort order and cover flag per photo.

### Multi-currency Display

**Currency conversion & rates API** — Public endpoints convert an amount between supported currencies and return the full rate table for a base currency, so prices can be shown in the traveller's preferred currency.
*Business value:* Lets international travellers see experience prices in their own currency, reducing booking friction.
- Supported currency set is configurable (defaults EUR, USD, GBP); unsupported codes are rejected.
- Converted amounts scaled to 2 decimals, HALF_UP; same-currency conversion returns the amount unchanged.

**Live rates via Frankfurter with cached + stale fallback** — Exchange rates are fetched from the free, key-less Frankfurter API and held in a thread-safe in-memory cache with a configurable TTL (default 1 hour); on provider failure a stale cached rate is served if available.
*Business value:* Keeps currency display fast and resilient without paid FX dependencies or per-request latency.
- Cache keyed by base currency; TTL configurable (default 3600s).
- If the provider fails and no cache exists, a clear 'rates unavailable' error is returned.
- Provider is abstracted behind an interface, allowing alternative rate sources.

## 3. Availability, bookings & pricing

### Availability slots & capacity

**Host availability slots** — Approved hosts create dated time slots (start/end time) for their approved experiences, each with a guest capacity. Each slot tracks how many seats are already booked.
*Business value:* The supply side of the marketplace — without slots an experience cannot be booked.
- Only an APPROVED local profile can create slots, and only for their own APPROVED experience
- Capacity is 1–10 seats per slot (MVP cap); end time must be after start time and both must be in the future
- Each slot carries a live booked-seat count and a remaining-capacity figure derived from it
- Slot status is AVAILABLE, BLOCKED, or CANCELLED

**Block / unblock / delete slots** — Hosts can temporarily block a slot (stop new bookings), re-open it, or delete it entirely.
*Business value:* Lets hosts manage their calendar and pull availability without deleting history.
- Blocking or deleting is refused while the slot still has active bookings — the host must cancel those first (only possible more than 24h before start), so inside 24h a booked slot is effectively frozen
- A slot also auto-blocks itself when its booked seats reach capacity, and auto-reopens when capacity is freed by a cancellation/decline/expiry

**Public availability listing** — A public (no-login) endpoint returns the future, bookable slots for a given experience, with each slot flagged as to whether a private whole-slot buyout is still possible.
*Business value:* Powers the date/time picker travellers and guests see before booking.
- Only future AVAILABLE slots with remaining capacity are shown
- Private-buyout availability is surfaced per slot (only true while AVAILABLE and zero seats booked)
- Experience must be APPROVED or the listing is refused

**Seat reservation & concurrency control** — When a booking is created the slot's booked-seat count is incremented under a database row lock, and decremented again on cancel/decline/expire/reschedule.
*Business value:* Prevents overselling a slot when two people book the same moment.
- Slot is fetched 'for update' (pessimistic lock) during booking, cancellation and reschedule
- A duplicate-active-booking guard plus a DB integrity fallback stops the same user/guest-email double-booking one slot
- Booked count never drops below zero on release

### Booking creation & channels

**Logged-in traveller booking** — A registered traveller books a slot, choosing guest count and (optionally) age-band breakdown; the booking is created in PENDING_PAYMENT and proceeds to checkout.
*Business value:* The primary booking path for account holders.
- Caller must have the traveller role and pass trust-&-safety and consent checks before booking
- Host account must also be in good standing (not restricted)
- Experience must be APPROVED and the slot valid/future with enough capacity
- A combined 'create booking + checkout' endpoint creates the booking and a payment checkout session in one call

**Guest (no-account) booking** — Anyone can book without an account by supplying name, email and phone plus explicit terms/consent acceptance; the booking is looked up later by reference + email.
*Business value:* Removes the sign-up barrier, capturing travellers who won't create an account.
- Guest must accept Terms and pass the current consent version, which is recorded with IP address, user-agent and timestamp for audit
- Guest email is normalised and de-duplicated against active bookings for the slot
- Endpoint is rate-limited per client IP
- Guest can later retrieve their booking via booking reference + matching email; phone/email are stored as unverified flags

**Admin manual booking (offline payment)** — An admin creates a booking on a guest's behalf that is CONFIRMED immediately with no online payment (collected offline).
*Business value:* Lets the platform handle phone/in-person bookings and comps without forcing online checkout.
- Skips the payment/pending step — booked seats are blocked and a confirmation is sent right away
- Supports age bands and private buyout like other channels
- No promo discount is applied on admin bookings

**Age-band party composition** — A party can be split into adults, teens, children and infants; all four count toward seats but price is weighted per band (adults & teens full, children half, infants free by default).
*Business value:* Supports family-friendly pricing and correct seat counting in one model.
- Band rates are configurable; defaults adult/teen 1.0, child 0.5, infant 0.0
- Age-gating: experiences with a minimum age reject teen/child/infant bands that fall below it (e.g. an 18+ tour blocks teens)
- If no bands are supplied, the whole guest count is treated as adults (backward compatible)
- Seats consumed = total head count; billable units = weighted band sum

**Booking reference & notes** — Every booking gets a unique human-readable reference (LB-XXXXXXXXXXXX) and can carry a traveller note plus a host response note.
*Business value:* Gives customers and support an easy handle and a lightweight message trail on the booking.
- Reference uses an unambiguous alphanumeric alphabet (no easily-confused characters)
- Traveller note up to 1000 chars; host can attach a response note on accept/decline/reschedule

### Shared vs private-buyout booking

**Booking mode (shared / private-allowed / private-only)** — Each experience declares whether it is shared, can be booked either shared or as a private whole-slot buyout, or is private-only.
*Business value:* Lets hosts sell both join-in group experiences and exclusive private sessions from the same listing.
- SHARED: individual guests join up to capacity
- PRIVATE_ALLOWED: a slot can be either shared or bought out, but the private option disappears once any shared guest has booked, and a buyout closes the slot to others
- PRIVATE_ONLY: every booking reserves the whole slot at the private price
- A non-shared mode requires a flat private price on the experience

**Private whole-slot buyout** — A private booking charges the host's flat private price and blocks the entire slot capacity regardless of party size.
*Business value:* Monetises exclusivity — one buyer reserves the whole experience.
- Allowed only when no seats are yet booked on the slot
- Charged at the flat private price as-is (no per-person calculation and no private-specific discount), though promo codes still apply on the flat price
- Refused for experiences listed on external booking platforms, and refused if the mode doesn't permit it

### Booking lifecycle & states

**Booking status model** — A booking moves through a defined set of states from request/pending-payment to confirmed, completed, or one of several terminal cancellation/expiry states.
*Business value:* A single source of truth for where every booking stands, driving capacity, payouts and notifications.
- States: REQUESTED, ACCEPTED, PENDING_PAYMENT, CONFIRMED, DECLINED, CANCELLED_BY_LOGGED_IN_USER, CANCELLED_BY_LOCAL, CANCELLED_BY_ADMIN, CANCELLED_MINIMUM_NOT_MET, COMPLETED, EXPIRED
- REQUESTED/ACCEPTED/PENDING_PAYMENT/CONFIRMED are 'active' and hold a seat; others are terminal and release it
- Confirmation happens when payment is marked paid (or immediately for admin bookings)

**Host accept / decline** — For request-style bookings the host can accept (moving to ACCEPTED) or decline (releasing the seat), each with an optional note.
*Business value:* Gives hosts control over who joins their experience.
- Only REQUESTED bookings can be accepted/declined; pending-payment or confirmed bookings must use the cancellation flow instead
- Decline releases the held seat, reopens the slot if it was full, and notifies the waitlist
- Both actions notify the customer (traveller or guest)

**Cancellation by traveller / host / admin** — Pending-payment or confirmed bookings can be cancelled by the traveller, the host, or an admin, each recorded as a distinct cancellation state with a reason.
*Business value:* Clear, auditable cancellation paths with the right refund treatment per actor.
- Hosts may only cancel more than 24h before start; closer in they must contact support
- Each cancellation releases slot capacity, reopens the slot if needed, notifies the waitlist, and triggers refund handling
- Cancellation reason is stored; both host and customer are notified

**Pending-payment expiry & auto-confirm** — A scheduled sweep expires bookings left unpaid past a configurable window (default 15 min), releasing their seats; if payment did land they are auto-confirmed instead.
*Business value:* Frees abandoned seats automatically so inventory isn't locked by stalled checkouts.
- Runs on a fixed schedule (default every 60s), processing up to 100 at a time oldest-first
- On expiry it cancels open Stripe sessions, releases the seat and sets EXPIRED
- A failed/expired payment can also immediately release a booking's seat without waiting for the sweep
- If a paid payment is found, the booking is confirmed and a confirmation sent

**Reschedule to another slot** — A host or admin can move a pending-payment or confirmed booking to a different slot, transferring its seats.
*Business value:* Handles date changes without forcing cancel-and-rebook.
- Validates the new slot has capacity, then releases the old slot (reopening it / notifying its waitlist) and blocks the new one
- Both customer and host are notified of the reschedule

**Completion with safety gate** — A host marks a confirmed booking COMPLETED after the experience, which unlocks reviews — but only once required safety checklists are done.
*Business value:* Confirms delivery, gates payout/review flows, and enforces the safety checklist.
- Only CONFIRMED bookings can be completed
- Both the traveller's and the host's safety checklist (where applicable) must be marked complete first
- Completion notifies the customer that they can now leave a review

**Guaranteed-departure / minimum-not-met cancellation** — Slots below a platform minimum (default 3 guests) trigger an under-booked notice to the host, who may cancel the slot with full refunds to all guests up to a deadline.
*Business value:* Protects hosts from running uneconomic sessions while guaranteeing guests a full refund.
- A scheduled job notifies hosts once per under-booked slot inside a notice window (default 36h to 24h before start)
- Host cancellation is allowed only while below the minimum and before the deadline (default 24h before start); it cannot be used on slots whose capacity is itself below the minimum
- All active bookings are fully refunded and set to CANCELLED_MINIMUM_NOT_MET, the slot is CANCELLED, and customers are notified
- The platform never auto-cancels — the decision stays with the host

**No-show outcome on bookings** — Bookings carry a separate attendance outcome (none / host-no-show / customer-no-show) set when an admin verifies a no-show report, independent of the lifecycle status.
*Business value:* Records who failed to show and drives the correct refund/payout without overloading the status field.
- A verified HOST no-show fully refunds the customer and cancels the booking; a verified CUSTOMER no-show is informational and completes the booking (host keeps payment)
- Either party (including guests via reference+email) can file within 48h of start; admin verifies
- A host also has an operational in-person show/no-show mark per booking, separate from the admin verdict
- Admins can reset/clear a no-show flag (without reversing a refund)

**Reliability summaries** — Derived cancellation, no-show and completion counts are exposed per host, per experience and per customer.
*Business value:* Surfaces trust signals for vetting hosts and flagging unreliable customers.
- Host/experience: host-initiated cancellations, distinct host-no-show slots, completed bookings
- Customer: own cancellations, customer-no-shows, completed bookings

### Pricing engine

**Booking price calculation** — Shared bookings are priced as per-person price × weighted billable units (age bands); private bookings use the flat private price. Promo/referral discounts then reduce the experience total.
*Business value:* Turns party composition and booking mode into the amount a customer owes.
- Per-person price and currency come from the experience
- Original amount, discount amount and final total are all recorded on the booking
- Promo codes can be stacked (multiple combinable codes) and a primary code is recorded for display

**Service fee vs host commission split** — A customer-facing service fee is added on top of the experience price, while a separate host commission is deducted from the host's earnings — two independent rates.
*Business value:* Defines how the platform makes money on each side of the transaction.
- Customer is charged experience gross + service fee + service-fee VAT
- Host payout = host gross − commission − commission VAT
- Both rates are resolved per booking with scope precedence: experience → host → category → city → platform → config default (commission default 20%, service fee default 2.5%)
- Time-boxed rate rules let admins run promos (e.g. a reduced-commission month)

**VAT computation** — The engine computes VAT on the experience leg (by place of supply, category and date) and on platform fees, and applies the correct commission-VAT treatment based on the host's VAT status.
*Business value:* Keeps the marketplace tax-compliant across host types and jurisdictions.
- Host VAT status: NL-registered, NL-not-registered (KOR/private), EU-other-registered, or non-EU
- Non-registered/non-EU hosts charge no VAT on the experience; commission VAT treatment is STANDARD, NOT_REGISTERED (host bears it), REVERSE_CHARGE (EU), or OUT_OF_SCOPE (non-EU)
- Experience VAT can be gross- or net-entered; fee VAT uses the home-country standard rate (default NL 21%)
- Place of supply currently defaults to the platform home country

**Cost-bearer / discount absorption** — For each promo discount the engine splits who bears it — host, platform, or a configured split — so the host can still earn on a platform-funded discount.
*Business value:* Lets the platform fund promotions without quietly cutting host earnings.
- HOST-borne discounts reduce the host's gross (legacy default); PLATFORM-borne are absorbed by the platform margin; SPLIT uses a configured platform-share percentage
- The host's commission/payout base is the un-absorbed gross, so platform-funded discounts don't reduce host pay
- Stacked multi-code bookings sum each code's platform share

**Settlement breakdown & cash conservation** — Every booking produces a full immutable breakdown (experience net/gross/VAT, commission + its VAT, service fee + its VAT, customer total, host payout, platform keep, VAT remitted) written onto the payment.
*Business value:* Auditable, penny-accurate financials for payouts, invoicing and reconciliation.
- Invariant enforced: customer total = host payout + platform keep + VAT remitted (within rounding)
- Every line rounded half-up to 2 decimals; verified against golden test vectors
- Can be computed as a preview without persisting, or applied to a payment with all snapshot fields

**Admin rate management & audit** — Admins manage commission, service-fee and VAT rate rules (create, deactivate, list) via admin endpoints, with a change audit trail.
*Business value:* Gives the business levers to run pricing promotions and tax changes without code deploys.
- Rules are scoped (platform/city/category/host/experience) and time-boxed (effective-from / effective-to)
- Every rate change is recorded in an audit trail queryable by rate type
- VAT rates can be assigned per country/category/date

**Cancellation refund policy** — Refund percentages are driven by configurable policies keyed on who cancelled and how many hours before start, applied to the booking total.
*Business value:* Consistent, admin-tunable refund rules instead of ad-hoc decisions.
- Policies define a cancelled-by actor, an hours-before-start window, and a refund percentage; the matching active policy is applied
- Refund is capped at the booking amount and floored at zero
- Admins can create, update and deactivate policies

## 4. Payments, payouts, invoicing & growth money

### Checkout & Payments (Stripe)

**Stripe hosted checkout for bookings** — Turns a booking into a payment and creates a Stripe-hosted checkout session, returning a URL the customer is redirected to in order to pay. The full financial breakdown (price, VAT, commission, service fee, host payout) is snapshotted onto the payment when it is created.
*Business value:* Lets travellers pay securely for experiences without LocalBuddy handling card data, while capturing all the financial detail needed for accounting and host settlement.
- Only one active payment (PENDING/PROCESSING/PAID) is allowed per booking; a second attempt is rejected.
- Checkout is only allowed for bookings in PENDING_PAYMENT or ACCEPTED status.
- Checkout sessions are short-lived (~31 min Stripe floor); the booking-expiry job proactively expires the Stripe session at the internal seat-hold deadline.
- Currency defaults to EUR; amounts charged in cents.
- An existing in-progress (PROCESSING) checkout with a live URL is re-returned rather than duplicated.

**Guest (no-login) checkout** — Unauthenticated guests can create a pending payment, look up their payment, and start checkout for a guest booking by supplying the booking reference plus the matching guest email. These public endpoints are rate-limited per client IP.
*Business value:* Removes the friction of forcing account creation, capturing buyers who book as guests while still protecting against abuse.
- Only bookings whose source is GUEST_USER are accessible; the reference + email must match or the booking is reported 'not found'.
- Rate-limited per IP separately for create, lookup, and checkout.

**Stripe webhook processing** — Receives and signature-verifies Stripe webhook events, then marks payments paid, expired or failed. On successful payment it confirms the booking, records the host's earning, generates invoices, redeems promo/referral codes and (for gift-card purchases) activates the card.
*Business value:* Ensures bookings are only confirmed once money is actually captured, and keeps earnings, invoicing and notifications consistent with real payment outcomes.
- Webhook signature is verified against a configured secret; unsigned/misconfigured calls are rejected.
- Duplicate webhook events are detected and ignored (idempotent) by storing every event with its provider event id.
- Handles checkout.session.completed, checkout.session.expired and async_payment_failed.
- Failed/expired sessions mark the payment FAILED, release any reserved gift-card balance, and immediately free the booked seat instead of waiting for the periodic sweep.
- Safety net: if a (late) payment lands after the seat hold already expired and the booking can no longer be honoured, it is automatically refunded in full instead of confirmed.
- Distinguishes a gift-card purchase session (activates the card) from a booking-payment session via Stripe metadata.

**Payment status lifecycle** — Each payment moves through a defined set of states covering pending, processing, paid, failed, cancelled, and refund states (full, partial, refund-pending, refund-failed).
*Business value:* Gives finance and support a precise, auditable view of where every transaction stands.
- States: PENDING, PROCESSING, PAID, FAILED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, REFUND_PENDING, REFUND_FAILED.
- Captured payment method type (card, debit card, Apple Pay, Google Pay, Klarna, PayPal, bank transfer, or unknown).
- Provider abstraction supports STRIPE, PAYPAL, ADYEN, MANUAL though only Stripe is implemented.

### Refunds & Cancellation Policy

**Configurable cancellation refund policy** — Admins define refund policies as rules keyed by who cancels (guest, host, or platform) and how many hours before the experience start the cancellation happens, each granting a refund percentage. The system picks the matching policy for a given cancellation and computes the refund amount.
*Business value:* Encodes the marketplace's cancellation terms in one place so refunds are consistent, fair, and adjustable without code changes.
- Policies have a name, actor, min/max hours-before-start window, refund percentage, and active flag; can be created, updated, and deactivated.
- Refund = booking total x policy percentage, clamped to the booking amount.
- Hours-before-start is computed from the slot start time; past start counts as zero hours.

**Automated refund execution with gift-card split** — When a booking is cancelled, the system calculates the policy refund and issues it: the gift-card-funded share is returned to the gift card, and only the remaining cash share is refunded via Stripe.
*Business value:* Customers are made whole correctly across mixed payment methods, and stored gift-card value is preserved as credit rather than cashed out.
- Pending/processing payments are simply cancelled (no money moved).
- A 0% refund moves no money; partial vs full refund sets PARTIALLY_REFUNDED or REFUNDED.
- Refunds fully covered by the gift-card return skip Stripe entirely.
- Stripe refund reason is inferred (duplicate, fraudulent, or requested-by-customer).
- Refund failures are captured as REFUND_FAILED with the failure reason.
- Separate full-refund path exists for platform/host-initiated cancellations (e.g. a slot cancelled for not meeting its minimum).

**Proportional host clawback on refund** — When a customer is refunded, the host's earning is reversed only in proportion to the refund actually given (0% refund leaves the host's earning fully intact; 50% reverses half).
*Business value:* Protects hosts from losing earnings on late cancellations while ensuring the platform never pays out money it had to refund.
- If the earning is still on hold/available it is reduced or reversed in place; if already paid out, a negative clawback entry is posted that nets against future earnings.

### Host Earnings Ledger & Payouts

**Host earnings ledger** — A ledger is the source of truth for every host's earnings, holds, clawbacks and adjustments. Earnings post when a booking is paid, sit on hold during a configurable window, then become payable.
*Business value:* Gives an accurate, auditable record of what each host has earned and is owed, preventing double-payment and supporting clean financial reporting.
- Entry types: EARNING, REVERSAL (clawback), ADJUSTMENT (manual +/-).
- Entry statuses: PENDING (in hold window), AVAILABLE (payable), PAID, REVERSED.
- Hold window = experience end + configurable hold hours (default 72h).
- Earnings are idempotent per payment so concurrent confirmations cannot double-post.
- A scheduled job releases holds to AVAILABLE once the hold window passes.

**Host earnings dashboard** — Authenticated hosts can view their lifetime net earnings, total paid out, amount available now, and amount still on hold, plus their full payout history.
*Business value:* Gives hosts transparency into what they've earned and when they'll be paid, building trust in the marketplace.

**Stripe Connect onboarding & automated payouts** — Hosts onboard to Stripe Connect (Express accounts) via a hosted onboarding link. Payouts batch a host's available ledger entries and, if the host is onboarded and payouts-enabled, automatically transfer the money via Stripe Connect.
*Business value:* Automates getting money to hosts and offloads payout compliance/KYC to Stripe.
- Connect account is created/reused per host and stored on their profile.
- Entries are reserved against a payout so the same earnings can never be paid twice.
- If a host isn't onboarded, the payout stays PENDING for manual/offline disbursement.
- Successful auto-disbursement marks the payout PAID, settles its entries, and generates a payout statement.

**Scheduled payout runs** — A scheduler automatically pays out hosts whose available balance meets a minimum, on a configurable cadence (weekly, biweekly, or monthly on a set day).
*Business value:* Removes manual payout work and gives hosts predictable, regular payment.
- Configurable minimum payout amount (default 25) and cadence (default monthly, day 1).
- Only acts on the cadence's payout day; runs after payout are no-ops once balance drops below the threshold.

**Admin payout management** — Admins can view all payouts across hosts, manually create a payout for a host, mark a pending/failed payout as paid (offline disbursement), and retry a failed Stripe payout.
*Business value:* Gives operations a manual override for payouts that fall outside the automated Connect flow and a safe way to recover from failures.
- Mark-paid and retry are guarded so the same earnings can never be settled (paid) twice.
- Failed Stripe payouts keep their entries reserved so they can be retried or settled offline; retrying a non-failed payout is rejected.
- Payout statuses: PENDING, PROCESSING, PAID, FAILED.

### Commission, Service Fee & VAT Pricing Engine

**Financial breakdown engine** — A pricing engine resolves all rates for a booking (commission, service fee, VAT on each leg) and writes a full immutable breakdown onto the payment: experience gross/net/VAT, commission and its VAT, service fee and its VAT, place of supply, what the platform keeps, and the host's cash payout.
*Business value:* Produces correct, tax-compliant numbers for what the customer pays, what the platform earns, and what the host receives - the financial backbone of every transaction.
- Customer is charged experience gross + service fee + service-fee VAT.
- Service fee is added on top of the (post-discount) experience price.
- Can compute a breakdown without persisting it (for previews).

**Configurable commission & service-fee rules** — Admins manage commission and service-fee rules scoped at different levels (platform, city, category, host, experience), with a more specific scope overriding a broader one. Each rule is time-windowed and can be deactivated.
*Business value:* Lets the business tune take-rate and fees by segment (e.g. promotional commission for a city or host) without code changes, with full precedence rules.
- Default platform commission is 20% if no rule matches.
- Scope precedence (low to high): PLATFORM, CITY, CATEGORY, HOST, EXPERIENCE.
- Rate changes are recorded in an audit trail viewable by admins.

**VAT handling by host status & place of supply** — VAT is computed per booking based on the host's VAT status and the place of supply, covering the experience leg and the commission leg differently. Configurable VAT rates are managed by admins.
*Business value:* Keeps the marketplace tax-compliant across NL-registered, non-registered (KOR), other-EU, and non-EU hosts, including reverse-charge and out-of-scope cases.
- Host VAT statuses: NL_REGISTERED, NL_NOT_REGISTERED, EU_OTHER_REGISTERED, NON_EU.
- Commission VAT treatments: STANDARD (reclaimable), NOT_REGISTERED (charged, non-reclaimable), REVERSE_CHARGE, OUT_OF_SCOPE.
- Commission VAT is only charged for NL-registered or NL-not-registered hosts.

**Discount cost-bearer in pricing** — When a promo/voucher discount is applied, the engine respects who bears the cost: host-borne discounts reduce the host's earning, platform-borne discounts pay the host on the full price (platform absorbs it), and split discounts share the cost by a configured percentage.
*Business value:* Lets the platform run promotions funded by itself, by hosts, or shared - without unfairly penalising hosts.

### Invoicing & Receipts

**Automated VAT invoices, receipts & payout statements** — On each confirmed payment the system issues a commission invoice to the host and a service-fee receipt to the customer; on each payout it issues a payout statement to the host. Documents snapshot the issuer's legal/VAT details and a gap-free invoice number so they are immutable.
*Business value:* Provides the legally-required tax documents to hosts and customers automatically, and gives the platform a clean audit trail.
- Invoice types: COMMISSION (to host), SERVICE_FEE_RECEIPT (to customer), PAYOUT_STATEMENT (to host).
- Statuses: DRAFT, ISSUED, SENT, VOID.
- Invoice numbers are sequential and gap-free per year under a row lock (e.g. LB-2026-0001).
- Generation is idempotent per booking/payout and invoice type.
- Service-fee receipt is only issued when a service fee was charged.
- Recipients are notified by email/notification when an invoice is issued (works for guest bookings too).
- VAT notes are added for reverse-charge, not-registered, and out-of-scope cases.

**Invoice access & PDF download** — Hosts and customers can list their own invoices and download any of them as a PDF; admins can list and download all invoices.
*Business value:* Self-service access to tax documents for users and full oversight for finance/admin.
- Non-admin access is restricted to invoices tied to the user's host profile or their own bookings.
- Invoices are rendered to PDF on demand.

**Company / issuer settings** — Admins maintain the platform's legal issuer details (legal & trading name, VAT/BTW number, Chamber-of-Commerce number, address, country, contact, IBAN, invoice number prefix) that appear on issued documents.
*Business value:* Centralises the company's billing identity so every invoice is correct and consistent, and adapts if company details change.
- Defaults to NL / 'LocalBuddy' / 'LB' prefix if not configured.

### Gift Cards

**Gift card purchase** — Authenticated users buy a gift card for a chosen amount, optionally addressed to a recipient with a name and message. A unique code is generated and the card stays non-spendable until the Stripe purchase payment succeeds, after which a webhook activates it.
*Business value:* Creates a new revenue stream and a gifting/acquisition channel that brings new customers to the marketplace.
- Codes are unique, human-friendly (e.g. LB-XXXX-XXXX-XXXX).
- Purchased cards are stored value and never expire (a configurable validity default of 365 days applies to other issuance paths).
- Statuses: PENDING_PAYMENT, ACTIVE, DEPLETED, CANCELLED, EXPIRED.

**Gift card redemption at checkout & balance management** — A gift-card code can be applied at booking checkout to cover part or all of the amount; the card balance is reserved/decremented and only the remainder is charged to Stripe. Direct redemption against a balance is also supported.
*Business value:* Lets customers spend stored value seamlessly toward bookings, increasing conversion and using up issued credit.
- Concurrent redemptions are serialised under a row lock so balance can't be double-spent.
- If a gift card covers the whole amount (or all but a sub-Stripe-minimum remainder the platform absorbs), the booking completes with no Stripe charge.
- Reserved balance is returned to the card if checkout fails, and the gift-card share of any refund is returned to the card.
- Card auto-marks DEPLETED at zero balance and reverts to ACTIVE if funds are returned.
- Redemptions are recorded against the booking and user.

**Gift card balance lookup & admin management** — Anyone can check a gift card's balance by code (public); buyers can list cards they purchased; admins can list all gift cards and cancel a card.
*Business value:* Gives holders self-service visibility and gives operations control over the gift-card book.
- Expired cards are detected and flagged on lookup.

### Promo Codes & Vouchers

**Admin promo code management** — Admins create and list promo codes with rich configuration: percentage or fixed-amount discount, currency, max discount cap, minimum spend, total and per-user redemption limits, active window (start/expiry), and active flag.
*Business value:* Gives marketing a flexible discounting tool to run campaigns with guardrails on spend and abuse.
- Discount types: PERCENTAGE (capped at 100) or FIXED_AMOUNT.
- Validation prevents negative minimums, non-positive limits, and expiry-before-start.

**Promo code validation (guest & authenticated)** — Customers (logged-in or guest) can validate a code against a booking amount/currency and receive the resulting discount and final price, or a clear reason it's invalid.
*Business value:* Lets the frontend show real-time discount feedback before purchase, improving conversion and reducing checkout errors.
- Checks active flag, time window, currency match, minimum spend, total and per-user/guest-email redemption limits.
- Per-user-limited codes require an identifying email for anonymous guests.

**Targeted vouchers & cost-bearer config** — Promo codes can act as targeted vouchers: issued to a specific user id or email (only usable by that customer), with a configurable cost-bearer (host, platform, or split by percentage) and a combinable flag.
*Business value:* Enables personalised vouchers (e.g. service-recovery credits) and lets the business decide who funds each discount.
- Cost bearer: HOST (default), PLATFORM, or SPLIT (requires a platform-share % between 0 and 100).
- Targeted codes reject use by anyone other than the issued customer.

**Multi-code stacking & redemption recording** — One or more codes can be applied to a booking; combinable codes stack (percentage codes apply first, then fixed-amount, each on the running total, clamped at zero). On a paid booking, each applied code's redemption is recorded and its usage counter atomically incremented.
*Business value:* Supports richer promotions while enforcing combinability rules and accurate, race-safe redemption counting.
- If more than one code is supplied, every code must be combinable, else only one may be used.
- Redemption recording is idempotent per booking+code; the usage count is incremented at the DB level to avoid lost updates.

### Referrals

**Personal referral codes** — Each user can get or create their own unique referral code and retrieve it later.
*Business value:* Drives organic growth by giving every user a shareable code to invite others.
- Codes are unique and prefixed (e.g. LB + 8 chars); created on demand with no redemption cap by default.

**Referral code validation** — Referral codes can be validated by authenticated users or guests, returning validity plus the referrer's identity, with rules preventing misuse.
*Business value:* Lets the frontend confirm a referral before booking and attributes the referral to the right referrer.
- Rejects inactive codes, codes past their redemption limit, a user using their own code, and re-use by the same user or guest email.

**Referral redemption on paid booking** — When a booking carrying a referral code is paid, a referral redemption is recorded against the referrer with a reward status, and the code's redemption counter is incremented.
*Business value:* Captures successful referrals so referrers can be rewarded, closing the growth loop.
- Reward statuses: PENDING, ELIGIBLE, PROCESSED, CANCELLED; redemption is created as ELIGIBLE.
- Reward amount is not yet computed (left null) at redemption time; idempotent per booking.

### Admin Deals & Discounts

**Admin deal management** — Admins create, update, list, fetch, and deactivate promotional deals with a name, description, discount (percentage or fixed amount), scope, optional time window, display priority, and badge text.
*Business value:* Powers merchandised promotions and badges across the marketplace to drive demand on chosen segments.
- Deal types: SPECIAL, SEASONAL, LAST_MINUTE, EARLY_BIRD, FLASH.
- Scopes: GLOBAL, CITY, EXPERIENCE, CATEGORY - the matching target id is required for non-global scopes.
- Validation enforces non-negative discount, percentage <= 100, and end-after-start.

**Public live-deals browsing** — A public endpoint returns deals that are currently active and within their time window, ordered by priority, with optional filters for deal type, city, experience, or category (global deals always included).
*Business value:* Surfaces current promotions to travellers to spur bookings, without requiring login.

## 5. Reviews, safety, trust & attendance

### Reviews & rating aggregation

**Two-way reviews (traveler↔host)** — After a booking is completed, the traveler can leave a 1-5 star rating with an optional comment about the host/experience, and the host can leave a 1-5 star rating with comment about the traveler. Each direction is allowed only once per booking.
*Business value:* Builds the trust layer that lets travellers judge hosts and lets hosts judge repeat guests, the core of a curated marketplace.
- Reviews can only be created when the booking status is COMPLETED
- Traveler review requires the reviewer to be the booking's logged-in traveler; host review requires the reviewer to own the booking's local profile
- Comment limited to 2000 characters; rating must be 1-5
- Guest (no-account) bookings cannot be reviewed by the host
- Direction is recorded as TRAVELER_TO_HOST or HOST_TO_TRAVELER; one review per direction per booking is enforced

**Automatic rating aggregation** — Whenever a review is created, hidden, or unhidden, the system recomputes the running average rating and total review count for the affected host profile (from traveler reviews) or traveler (from host reviews).
*Business value:* Keeps the public star rating and review counts shown on profiles and listings always accurate without manual recalculation.
- Average is computed only from VISIBLE reviews, rounded to 2 decimals
- Host ratings aggregate onto the local profile; traveler ratings aggregate onto the user account
- Hiding/unhiding a review immediately re-averages, so moderation directly affects displayed scores

**Public review reading** — Anyone, without logging in, can read the publicly visible traveler-to-host reviews for a given host profile or a given experience, newest first.
*Business value:* Lets prospective travellers see social proof on listing and profile pages before booking.
- Only VISIBLE, TRAVELER_TO_HOST reviews are exposed publicly
- Available per local profile and per experience

**My reviews & reviews-about-me** — A logged-in user can list the reviews they have authored and the visible reviews written about them (whether they were reviewed as a traveler or as a host).
*Business value:* Gives users transparency into their own reputation and feedback history.
- 'About me' returns only VISIBLE reviews in either direction

**Admin review moderation** — Admins can list every review and hide or unhide any review (with an optional reason), removing it from or restoring it to public display.
*Business value:* Lets the platform suppress abusive, fake, or policy-violating reviews while keeping an audit reason and auto-correcting affected rating averages.
- Reviews have VISIBLE / HIDDEN status
- Moderation reason (max 1000 chars) and a moderated-at timestamp are recorded
- Hiding/unhiding triggers rating recomputation for the host or traveler

### User safety reporting

**General safety reports (legacy safety module)** — Any authenticated user can file a safety report flagging another user and/or a booking, choosing a category, a severity level, and a free-text description. Users can also list the reports they have filed.
*Business value:* Gives users a direct channel to flag unsafe behaviour, harassment, fraud, payment issues, or inappropriate content for platform review.
- Report types: UNSAFE_BEHAVIOR, HARASSMENT, NO_SHOW, FRAUD, PAYMENT_ISSUE, INAPPROPRIATE_CONTENT, OTHER
- Severity: LOW, MEDIUM, HIGH, CRITICAL (defaults to MEDIUM)
- Reported user and booking are both optional; you cannot report yourself
- Description required, max 3000 characters
- Statuses: OPEN, IN_REVIEW, RESOLVED, DISMISSED

**Admin handling of safety reports (legacy module)** — Admins can list all safety reports (optionally filtered by status) and move a report into review, resolve it, or dismiss it, attaching admin notes and a resolution note.
*Business value:* Provides a triage workflow so the safety team can work through user-submitted concerns and record outcomes.
- Closed (RESOLVED/DISMISSED) reports cannot be moved back to in-review
- Resolving or dismissing stamps a resolved-at time and stores admin + resolution notes (each max 2000 chars)
- Cannot re-resolve an already-resolved report or re-dismiss an already-dismissed one

**Booking-tied trust & safety reports (trust-safety module)** — A second, booking-centric reporting flow lets a traveler or host on a specific booking report the other party. The system automatically determines who is being reported from the booking's participants.
*Business value:* Lets either party raise a complaint tied to a concrete booking with the counterparty auto-identified, reducing mis-reporting.
- Requires a booking ID; only parties to that booking can report
- Report types: NO_SHOW, HARASSMENT, UNSAFE_BEHAVIOR, FRAUD, PAYMENT_ISSUE, INAPPROPRIATE_CONDUCT, OTHER
- Severity LOW/MEDIUM/HIGH/CRITICAL; description required, max 5000 chars
- Guest (no-account) bookings reporting the traveler is not yet supported via this endpoint
- Note: this exists alongside the legacy safety-reports module; the two are separate systems with separate tables

**Admin trust & safety report management** — Admins can list trust-safety reports (optionally by status) and update a report's status, severity, and admin notes.
*Business value:* Gives the safety team a richer status workflow for booking-linked complaints.
- Statuses: OPEN, IN_REVIEW, ACTION_REQUIRED, RESOLVED, DISMISSED
- Setting status to RESOLVED or DISMISSED stamps a resolved-at timestamp
- Admin notes max 5000 chars

### Account restrictions & enforcement

**User account restrictions** — Admins can place a restriction on a user — suspending the account, blocking them from booking, or blocking them from hosting — with a required reason, and can later deactivate the restriction.
*Business value:* Gives the platform concrete enforcement teeth to remove bad actors from booking or hosting following a safety investigation.
- Restriction types: ACCOUNT_SUSPENDED, BOOKING_BLOCKED, HOSTING_BLOCKED
- Reason required (max 3000 chars); records which admin created it
- Restrictions are active until deactivated, which stamps a deactivated-at time
- Admins can list all currently active restrictions

**Enforcement guards (suspend / book / host gates)** — The platform exposes checks that block a suspended user from acting, block a user from booking (if suspended or booking-blocked), and block a user from hosting (if suspended or hosting-blocked).
*Business value:* Turns restrictions into real-time gates that stop restricted users from transacting, not just records.
- requireUserNotSuspended, requireUserCanBook, requireUserCanHost throw an error when an active matching restriction exists

### Trip safety, SOS & emergency contact (live experience)

**Emergency contact on file** — A user can set, update, and view a single emergency contact (name, phone, optional relationship) stored against their account.
*Business value:* Ensures the platform has someone to reference if a traveller raises an emergency during a live experience.
- One emergency contact per user (upsert)
- Name and phone required; relationship optional

**Trip check-in / check-out events** — During a booking, the traveler can record a check-in and a check-out event, each optionally carrying GPS coordinates and a note, building a timeline of the trip.
*Business value:* Creates a lightweight self-reported trail of the traveller's movements for safety and dispute context.
- Event types CHECK_IN, CHECK_OUT, SOS
- Only the booking's traveler can record these events; note max 2000 chars
- Both traveler and host can view the full event timeline for the booking

**SOS panic alert** — A traveler can raise an SOS during a booking; the system records the event with optional location and message and immediately emails support with the booking reference, GPS coordinates, the message, and the traveller's emergency contact details.
*Business value:* Gives travellers an in-the-moment distress button that routes a complete situation brief to the support/safety team.
- Notifies support via a SAFETY_REPORT_CREATED email containing location and emergency-contact name/phone
- Only the booking's traveler can raise an SOS

**Admin SOS monitoring & resolution** — Admins can list all unresolved SOS events (oldest first) and mark an SOS as resolved.
*Business value:* Lets the safety desk see and clear active distress signals as they handle them.
- Only SOS-type events can be resolved; resolving stamps a resolved-at time

### No-show reporting & refund handling

**Report a no-show (host or customer)** — On a confirmed booking, the customer can report that the host failed to show (a refund claim) and the host can report that the customer failed to show (informational). Each report is filed within a fixed window after the experience start.
*Business value:* Protects customers from paying for experiences that didn't happen and gives hosts a record of guests who don't turn up.
- Only CONFIRMED bookings are reportable; the experience must have already started
- Reports must be filed within 48 hours of the start time, after which the user is told to contact customer service
- Subject is auto-derived: customer reports HOST, host reports CUSTOMER
- Only one open/approved report per party per booking; reason max 2000 chars
- Statuses: REQUESTED, APPROVED, REJECTED

**Guest (no-account) host no-show claim** — A guest customer without an account can file a host no-show refund claim using only their booking reference and guest email, through a public, rate-limited endpoint.
*Business value:* Extends no-show refund protection to walk-up guest bookings who never created an account.
- Verified by booking reference + matching guest email (same pattern as guest payment lookup)
- Only works for GUEST_USER bookings; always filed as a HOST no-show with no reporter user
- Rate-limited per client IP; same 48-hour window applies

**Admin verification of no-show reports** — Admins list reports (all or pending-only), then approve or reject each. Approving a host no-show fully refunds the customer and cancels the booking by admin; approving a customer no-show completes the booking (host keeps payment). Either outcome flags the booking.
*Business value:* Puts a human gate between a no-show claim and an actual refund, preventing abuse while still automating the payout/refund consequence.
- Host no-show approval → full payment refund + status CANCELLED_BY_ADMIN + attendance outcome HOST_NO_SHOW
- Customer no-show approval → status COMPLETED + attendance outcome CUSTOMER_NO_SHOW (host stays paid)
- Both set a no-show-marked timestamp on the booking
- Admins can remove a no-show flag from a booking (does not reverse a refund already issued)
- Only pending (REQUESTED) reports can be approved or rejected; resolution note/admin note max 2000 chars

**Per-booking attendance outcome flag** — Each booking carries an attendance outcome (none / host no-show / customer no-show) set when an admin verifies a no-show, recorded separately from the booking's lifecycle status.
*Business value:* Lets the platform track who failed to show independently of the booking state, feeding reputation and dispute history.
- Values: NONE, HOST_NO_SHOW, CUSTOMER_NO_SHOW; admins can reset to NONE

**Automatic booking completion** — A scheduled background job automatically marks confirmed past bookings as completed once the 48-hour no-show window has closed, but skips any booking that still has a no-show report awaiting admin verification.
*Business value:* Closes out finished bookings automatically (enabling reviews, payouts, etc.) without leaving them stuck, while respecting open disputes.
- Runs on a fixed delay (default every 5 minutes), processing up to 100 due bookings at a time
- Completion threshold configurable (default 48 hours after start)
- Bookings with a still-pending no-show report are left for the admin

### Geo check-in & arrival attendance

**Guest geofenced arrival check-in** — A confirmed traveler checks in on arrival by sending their device GPS location; the system only accepts the check-in if they are physically within the meeting-point geofence during the check-in time window, accounting for GPS error.
*Business value:* Confirms guests actually arrived at the right place and time, providing an operational arrival signal for hosts.
- Hard-gated: rejected if outside the geofence (default 300 m radius), if location accuracy is missing or worse than the max trusted accuracy (default 500 m), or outside the time window (default 15 min before to 15 min after start)
- Requires a meeting-point coordinate on the experience; rejects with a clear message if none is set
- Returns distance to meeting point and the geofence radius on success
- Idempotent — re-checking-in updates the same record; safe against concurrent double-submits

**Anonymous guest check-in** — A guest without an account can perform the same geofenced arrival check-in using their booking reference and email, via a public rate-limited endpoint.
*Business value:* Extends arrival confirmation to walk-up guest bookings.
- Verified by booking reference + guest email; only valid for GUEST_USER bookings
- Rate-limited per client IP; same geofence/window/accuracy gates as logged-in guests

**Host arrival check-in with optional photo** — A host checks in for a time slot by sending GPS coordinates and an optional live arrival photo. Unlike guests, hosts are never rejected for distance — the check-in always records, returning a warning and the measured distance if they are outside the geofence.
*Business value:* Lets hosts signal they have arrived (and prove it with a photo) while never being blocked by GPS imprecision, and proactively reassures guests.
- Soft check-in: outside-geofence or no-meeting-point still records, with a warning
- Optional live arrival photo uploaded to media storage (host only)
- Slot-level (not tied to a single booking); same time window as guests
- On host check-in, all confirmed guests on the slot are notified 'Your host has arrived' via email/in-app

**Host attendance roster** — For one of their slots, a host sees an attendance dashboard: their own arrival state (checked in, within geofence, distance, photo) plus a row per confirmed booking showing guest name/phone, party size, show status, and whether/when/how close that guest checked in.
*Business value:* Gives hosts a single live view of who has arrived so they can run the meet-up.
- Shows whether the meeting point is set and the geofence radius
- Each booking row indicates guest check-in presence, time, distance, and within-geofence flag

**Host marks guest show / no-show** — After meeting in person, the host marks each booking's guest(s) as SHOWED or NO_SHOW (one mark per booking).
*Business value:* Captures the host's in-person attendance judgement as an operational signal (explicitly not an automatic refund trigger).
- Guest show status: PENDING (default), SHOWED, NO_SHOW; cannot set back to PENDING
- Allowed only on CONFIRMED or COMPLETED bookings; stamps a marked-at time
- Distinct from the admin-verified no-show refund flow — this is a soft, host-recorded signal

**Check-in data retention purge** — A scheduled job deletes geo check-in records older than the retention window, since they are operational data rather than refund proof.
*Business value:* Limits storage of location data for privacy/compliance, keeping only recent operational signals.
- Default 90-day retention; purge job runs on a configurable delay (default daily)

### Booking safety checklist

**Per-booking safety acknowledgement checklist** — For an accepted or confirmed booking, each party (traveler or host) views and completes a safety checklist acknowledging public-meeting, communication-guidelines, personal-safety, and reporting-guidelines points before the experience.
*Business value:* Drives both parties through key safety guidance before they meet, with a recorded acknowledgement for compliance.
- Role context auto-resolved as TRAVELER or LOCAL based on the booking; only booking parties have access
- All four acknowledgements are mandatory to complete; only allowed for ACCEPTED or CONFIRMED bookings
- One checklist per booking per user; completion captures timestamp plus the client IP address and user-agent as proof of consent

## 6. Messaging, notifications, comms & engagement

### In-app messaging & conversations

**Traveler-to-host conversations** — Logged-in travelers can open a one-to-one message thread with the host of a specific experience. Starting a thread for an experience reuses the existing thread if one already exists rather than creating duplicates.
*Business value:* Lets guests ask questions before/after booking, building trust and reducing drop-off.
- A conversation is tied to an experience and has exactly two peer participants (CUSTOMER and HOST roles).
- You cannot start a conversation with yourself.
- Messages are capped at 5000 characters and the body is required.
- Listing 'my conversations' returns threads where the user is either traveler or host, newest-activity-first, each with an unread count.

**Send messages & read receipts** — Participants post messages into a thread and mark a conversation as read. Unread counts are computed from the user's last-read timestamp.
*Business value:* Standard chat experience with unread badges so users know when there's something new.
- Sending a message updates the conversation's last-activity time and pushes an in-app notification to every other participant with a 140-character preview.
- 'Mark read' stamps the participant's last-read time; unread count is messages after that point not sent by the viewer.
- Non-members are blocked from reading or posting; the system hides a thread's existence from non-members (returns 'not found').

**Sender labelling by role** — Each message records the role it was sent as (customer, host, or admin). Admin messages are displayed as 'Admin (Name)' so guests/hosts can tell support apart from the other party.
*Business value:* Transparency — users always know whether they're talking to the host or to LocalBuddy support.

**Admin conversation monitoring & takeover** — Admins can list every conversation in the system, read any thread without being a member, and reply into any thread. Replying transparently joins the admin as a participant and the message is visible to everyone.
*Business value:* Support and trust-and-safety can oversee and step into host/guest disputes.
- Admin reply auto-adds the admin as an ADMIN participant on first reply.
- Admin reads/replies bypass the membership check that applies to normal users.

**Admin private side conversations** — Admins can open a private thread with a single customer or a single host, optionally tied to an experience, with an optional subject and an optional first message.
*Business value:* Lets support reach out privately (e.g. resolving an issue) without exposing the conversation to the other party.
- Target's role determines thread type: ADMIN_CUSTOMER for a logged-in user, ADMIN_HOST for a host (LOCAL).
- Only a customer or a host can be targeted; any other role is rejected.
- Subject capped at 200 chars; optional first message capped at 5000 chars.

### Notifications (multi-channel delivery)

**In-app notification feed** — Each user has a personal in-app notification feed they can list, mark a single item read, or mark all read. In-app notifications are 'delivered' simply by being stored.
*Business value:* Central inbox surfacing booking events, new messages, announcements, reminders, etc.
- Feed returns the user's IN_APP notifications newest-first.
- Marking another user's notification read is blocked (treated as not found).
- Notifications carry a type, subject, message, related-entity type/id (e.g. CONVERSATION, BOOKING, ANNOUNCEMENT) and read state for deep-linking.

**Notification types catalogue** — A fixed set of ~29 notification types spanning booking lifecycle, guest bookings, safety reports, host-profile approval steps, waitlist/slot events, messaging, invoices, newsletters, announcements, wishlist & abandoned-booking reminders, and host-arrived check-in.
*Business value:* Standardized event taxonomy so the whole platform notifies users consistently.
- Includes BOOKING_* lifecycle, GUEST_BOOKING_CREATED, SAFETY_REPORT_*, LOCAL_PROFILE_* approval states, WAITLIST_SPOT_AVAILABLE, SLOT_UNDERBOOKED/CANCELLED, BOOKING_REMINDER/CONFIRMED, NEW_MESSAGE, INVOICE_ISSUED, SYSTEM_ALERT, NEWSLETTER_CONFIRM/NEWSLETTER, HOST/PLATFORM_ANNOUNCEMENT, WISHLIST_REMINDER, BOOKING_ABANDONED_REMINDER, HOST_ARRIVED.

**Multi-channel outbox with delivery worker** — Notifications are written to an outbox and a background worker picks up pending items in batches and delivers them. Supports IN_APP, EMAIL, and WhatsApp channels; SMS is defined but treated as not-yet-supported.
*Business value:* Reliable, asynchronous, retryable delivery decoupled from the request that triggered it.
- Scheduled processor runs on a configurable delay (default ~10s) and processes up to 20 pending notifications per cycle.
- Each item moves through PENDING → PROCESSING → SENT / FAILED / SKIPPED; failures record a reason; rows are locked for update to avoid double-send.
- EMAIL with no recipient is SKIPPED; unsupported channels (e.g. SMS) are SKIPPED with a reason.
- Email sends store the provider message id; WhatsApp sends only deliver inside the 24h customer-service window (no template support yet).

**Duplicate-send protection (dedupe keys)** — Every notification carries a unique dedupe key; creating a notification with a key that already exists is silently ignored, and a race on insert is handled gracefully.
*Business value:* Schedulers and event handlers can run repeatedly without spamming users with duplicates.

**Email provider abstraction (Console / Azure)** — Email delivery is pluggable: a console provider logs emails locally (default for dev) and an Azure Communication Services provider sends real email in production, selected by configuration.
*Business value:* Same code works in local development and production without changes.
- Provider chosen via app.email.provider (console default, azure for production).
- Configurable from-name and from-address; Azure provider fails gracefully if the connection string is missing.

**Combined email + in-app helper** — A convenience path creates the same notification on both the email and in-app channels at once, keeping a separate deduped record per channel.
*Business value:* Important events (e.g. booking reminders, waitlist openings) reach users both in-app and by email.

**Guest (account-less) notifications** — Notifications can be addressed to a raw email/phone for guests who booked or subscribed without an account, not just to registered users.
*Business value:* Guests who book without signing up still receive reminders, waitlist alerts, and announcements.

### Notification preferences

**Per-user notification preferences** — Each user can view and update four notification toggles: booking reminders, marketing emails, a master email switch, and an SMS switch.
*Business value:* Gives users control over what they receive, supporting consent/compliance.
- Defaults when no preference is saved: booking reminders ON, master email ON, marketing emails OFF (opt-in), SMS OFF.
- Master email switch gates non-critical email such as reminders and announcements.
- Marketing-emails flag is a dedicated opt-in for promotional content.

### Automated reminders

**Upcoming booking reminders** — A scheduler sends a one-time reminder for each confirmed booking once it enters a lead window before start (default 24 hours), to both registered travelers and account-less guests.
*Business value:* Reduces no-shows by reminding guests their experience is coming up.
- Runs on a configurable delay (default ~15 min); lead window configurable (default 24h).
- Registered travelers: reminder respects their booking-reminder preference and is sent in-app + email, plus WhatsApp if a phone exists.
- Guests: sent by email and WhatsApp using the booking's guest contact details.
- Dedupe key guarantees one reminder per booking regardless of how often the scheduler runs.

**Wishlist 'still interested?' nudges** — A scheduler nudges users about experiences they saved to their wishlist but haven't booked, sent at configurable age stages (default 2h / 24h / 48h after saving).
*Business value:* Re-engages browsers who saved something but didn't convert, recovering lost bookings.
- Stops nudging once the user has engaged (any booking in REQUESTED/ACCEPTED/PENDING_PAYMENT/CONFIRMED/COMPLETED for that experience).
- Only scans items within a max-age cap (default 72h) so it never nudges ancient saves forever.
- Sent in-app always; email only if the user's master email is enabled. Links back to the experience page.
- Dedupe key enforces once-per-stage.

**Abandoned-booking recovery nudges** — A scheduler re-engages people whose booking expired unpaid (EXPIRED status), inviting them to finish booking, at the same configurable stages (default 2h / 24h / 48h after expiry).
*Business value:* Recovers revenue from checkouts that timed out before payment.
- Because there is no cart, it targets EXPIRED bookings and links back to the experience to re-book.
- Registered users: in-app always + email if their email is enabled. Guests: email only.
- Dedupe key enforces once-per-stage; max-age cap bounds how old a booking it will chase.

### Contact & support

**Public 'Contact Us' form** — Any visitor (no login) can submit a contact-us message with name, email, subject, and message. The submission is emailed to the configured support admin and the visitor gets a friendly acknowledgement.
*Business value:* Gives prospects and guests a support channel and routes it to the right inbox.
- Rate-limited per client IP to prevent abuse (429 on excess).
- Captures the sender's IP address and user-agent in the message sent to support.
- Requires a configured support admin user to exist; otherwise the submission is rejected.
- Name ≤150, subject ≤200, message ≤5000 chars; email must be valid.

### Host & platform announcements + following

**Host announcements (broadcast)** — Approved hosts can broadcast an announcement to their followers, their past/confirmed guests, or both. Delivery goes in-app plus email (email respects each recipient's preference).
*Business value:* Lets hosts promote new dates, news, or offers to an engaged audience, driving repeat bookings.
- Audience options: MY_FOLLOWERS, MY_GUESTS, or BOTH; ALL_HOSTS is reserved for platform announcements and rejected here.
- Only APPROVED hosts may post; subject ≤200, body ≤5000 chars.
- Guests = travelers with a CONFIRMED or COMPLETED booking; account-less guests are reached by email.
- Each announcement records its computed recipient count; host can list their own announcements newest-first.

**Platform announcements to all hosts** — Admins can send a platform-wide announcement delivered to every host on the platform, in-app plus email (email respects preference).
*Business value:* Operational/policy comms to the entire host base from one place.
- Targets all users with the LOCAL (host) role; records recipient count.
- Admins can also list all announcements (host and platform) across the system.

**Follow / unfollow a host** — Travelers can follow a host (by local-profile id) to receive that host's announcements, unfollow, and list the hosts they follow.
*Business value:* Builds a subscriber base for hosts and a personalized follow graph for travelers.
- Following is idempotent (duplicate follow is a no-op).
- Followers form the MY_FOLLOWERS audience for host announcements.

### Newsletter

**Public newsletter subscribe (double opt-in)** — Anonymous visitors can subscribe to the newsletter by email. The subscription stays PENDING until the emailed confirmation link is clicked (double opt-in).
*Business value:* Grows the marketing list compliantly with verified consent.
- Subscribe/confirm/unsubscribe endpoints are all rate-limited per client IP.
- Captures an optional audience segment and a 'source' tag.
- Re-subscribing an already-confirmed email is a no-op; a unique unsubscribe token is always assigned.

**Confirm & one-click unsubscribe** — Tokenized links confirm a pending subscription or unsubscribe with one click, no login required.
*Business value:* Frictionless, compliant list management with auditable confirm/unsubscribe timestamps.
- Confirm sets status CONFIRMED with a confirmed-at time; unsubscribe sets UNSUBSCRIBED with an unsubscribed-at time.
- Lifecycle states: PENDING → CONFIRMED → UNSUBSCRIBED.
- Every broadcast email includes a personalized unsubscribe link.

**Logged-in subscribe (segmented)** — A logged-in user can subscribe themselves; the subscription is segmented by role (host vs traveler) and skips email confirmation if their account email is already verified. They can also check their own subscription status.
*Business value:* Smoother opt-in for known users while keeping segmentation for targeted sends.

**Admin subscriber list & broadcast** — Admins can list all newsletter subscriptions and broadcast a newsletter to all CONFIRMED subscribers in a chosen audience, getting back the recipient count.
*Business value:* Operates the email marketing channel end to end.
- Audience targeting: TRAVELER, HOST, or ALL (ALL also includes the traveler and host segments).
- Sends flow through the deduped notification outbox; each broadcast uses a unique batch id so the same person isn't double-sent within a blast.
- Body up to 20,000 chars.

### Wishlist / favourites

**Save & manage favourite experiences** — Travelers can add an experience to their wishlist, remove it, list their saved experiences, and check whether a specific experience is saved.
*Business value:* Lets users bookmark experiences to come back to, and feeds re-engagement reminders.
- Only APPROVED experiences can be wishlisted; adding an already-saved item is idempotent.
- Wishlist is returned newest-first.

**Lightweight wishlist-ids lookup** — An endpoint returns just the ids of the user's wishlisted experiences so the UI can render filled/empty hearts across listings without one call per experience.
*Business value:* Efficient rendering of favourite state across browse/search pages.

**Batch add (guest favourites merge)** — Travelers can add many experiences at once (up to 100), used to merge a guest's locally-saved favourites into their account after they log in.
*Business value:* Preserves favourites a visitor saved before signing up.
- Idempotent: unknown, non-approved, or already-saved ids are silently skipped; returns the resulting wishlist.

### Waitlist

**Join a waitlist for a full slot** — When an availability slot is full, travelers (logged in) or guests (with name/email/phone) can join its waitlist for a given party size.
*Business value:* Captures demand on sold-out slots and gives a path to convert when seats free up.
- Cannot join a past or cancelled slot; cannot join if seats are still available for the requested party size (told to book directly instead).
- Party size between 1 and 10.
- Duplicate waitlist entries for the same slot are rejected ('already on the waitlist').
- Statuses: WAITING → NOTIFIED → CONVERTED / CANCELLED.

**View & leave waitlist** — A logged-in user can list their own waitlist entries (newest-first) and leave a waitlist, which cancels their entry.
*Business value:* Self-service control over waitlist commitments.
- Leaving sets the entry to CANCELLED; users can only cancel their own entries.

**Spot-opened notifications (first-come, first-served)** — When seats free up on a slot, everyone currently waiting is notified that a spot opened — emailed, and in-app for logged-in users — and told it's first-come, first-served. Notified entries move to NOTIFIED so they aren't emailed repeatedly.
*Business value:* Converts waitlisted demand into bookings the moment capacity opens, fairly and without spam.
- Only fires when the slot is AVAILABLE with remaining capacity > 0.
- Each waiting entry gets a deduped notification keyed to the entry id; status flips to NOTIFIED with a notified-at timestamp.
- Whoever books and pays first secures the seat (no held reservation).

## 7. AI, integrations, admin & platform ops

### AI Assistant (Claude-powered)

**AI availability check** — Reports whether AI features are switched on in the current environment so the app can show or hide AI tools accordingly.
*Business value:* Lets the product degrade gracefully when no AI provider is configured, avoiding broken buttons.
- GET /api/ai/status returns {configured: true/false}
- AI is dormant until an Anthropic API key is configured; all AI calls return a clear error when not configured
- Runs on Anthropic's Claude (model and token limit are configurable; default max ~1024 tokens per response)

**Listing copy assistant for hosts** — Generates marketing copy for an experience listing: a short punchy description plus a longer 2-4 paragraph detailed description, from a title and optional city, category and host notes.
*Business value:* Speeds up host onboarding and improves listing quality/consistency without hiring copywriters.
- POST /api/ai/listing-assistant; title required (<=150 chars), city/category optional (<=120), highlights optional (<=2000)
- Returns shortDescription (~250 chars) and detailedDescription
- Prompted to be vivid but honest and inclusive; if the model returns non-JSON, falls back to using the raw text as the detailed copy

**Itinerary suggestion** — Produces a friendly, practical local day itinerary for a traveller in a given city, tailored to interests, available time and party size.
*Business value:* Adds a discovery/inspiration hook that can drive travellers toward booking experiences.
- POST /api/ai/itinerary; city required (<=120), interests optional (<=1000), durationHours and partySize optional
- Returns free-text itinerary with rough timings and local tips
- Prompted to keep suggestions realistic and safe

**Content moderation classifier** — Classifies free text for policy violations (hate, harassment, sexual content involving minors, violence, illegal activity, spam, or sharing contact details to evade the platform) and returns a flag plus a short reason.
*Business value:* Provides an automated first line of defence for user-generated content and trust & safety.
- POST /api/ai/moderation; text required (<=5000)
- Returns {flagged: boolean, reason: string}
- Fail-open by design: if the verdict cannot be parsed, content is NOT blocked (returns flagged=false)
- All three AI endpoints require an authenticated user

### WhatsApp integration

**Click-to-chat link builder** — Builds a standard wa.me WhatsApp link for any phone number with an optional pre-filled message.
*Business value:* Enables instant WhatsApp contact with zero integration cost or credentials — always available.
- GET /api/whatsapp/click-to-chat?phone=&message=
- Phone is normalised to digits only; a valid number is required
- No WhatsApp account or API credentials needed

**Booking counterpart contact link** — For a given booking, returns a WhatsApp click-to-chat link to message the other party — host messaging traveller or traveller messaging host — pre-filled with the booking reference.
*Business value:* Lets hosts and guests coordinate logistics directly over WhatsApp without exposing the platform to messaging costs.
- GET /api/whatsapp/bookings/{bookingId}/contact; caller must be the booking's traveller or host (otherwise treated as not found)
- Picks the counterpart's phone (host user phone, or traveller's user phone / guest phone for guest bookings)
- Returns counterpartRole (HOST/TRAVELER) and the link; errors if the other party has no phone on file
- No inbound WhatsApp webhook exists — this is outbound link generation only

**WhatsApp Business API sending (admin)** — Admins can send outbound WhatsApp text messages through Meta's Cloud (Business) API.
*Business value:* Supports operational/support outreach to users via official WhatsApp messaging.
- GET /api/admin/whatsapp/status reports whether sending is configured; POST /api/admin/whatsapp/send sends a text (toPhone <=40, message <=4000)
- Config-guarded: dormant until access token and phone-number ID are set; returns a clear error and suggests click-to-chat when unconfigured
- Returns provider message ID and status; ADMIN-only

### Mobile wallet passes

**Wallet availability + links for a booking** — Tells the app which mobile wallets are configured (Apple, Google) and returns the relevant save links/paths for a booking's ticket.
*Business value:* Gives guests a familiar 'Add to Wallet' ticketing experience, improving day-of attendance.
- GET /api/bookings/{bookingId}/wallet; only the booking's traveller or host may access (otherwise not found)
- Returns googleConfigured, googleSaveUrl, appleConfigured, applePassPath
- Requires the booking to have a booking reference

**Apple Wallet pass (.pkpass)** — Generates and downloads a properly signed Apple Wallet event-ticket pass for a booking, showing the experience, date/time, location, host, guest count, booking reference and a QR code.
*Business value:* Native iPhone ticketing that surfaces the booking at the right time and place.
- GET /api/bookings/{bookingId}/wallet/apple.pkpass returns a signed .pkpass file
- Builds and PKCS#7-signs the pass with the issuer's Pass Type ID certificate and Apple WWDR certificate (BouncyCastle)
- Event-ticket layout with QR barcode encoding the booking reference; relevantDate set from the slot start
- Config-guarded: dormant until Apple Pass Type ID, Team ID, .p12 certificate and WWDR certificate are provided

**Google Wallet save link** — Generates a 'Save to Google Wallet' link (signed JWT) encoding a generic pass with the experience, booking reference, when/where, host and guest count plus a QR code.
*Business value:* Native Android ticketing parallel to Apple Wallet.
- GET /api/bookings/{bookingId}/wallet/google returns {saveUrl}; link form is https://pay.google.com/gp/v/save/<jwt>
- JWT is RS256-signed with a Google service-account key
- Config-guarded: dormant until issuer ID, class ID and service-account credentials are set (issuer must pre-create a Generic pass class)

### Add-to-calendar

**Booking .ics download** — Produces a standards-compliant iCalendar (.ics) file for a booking that imports into any calendar app, with title, time, location and booking reference.
*Business value:* Reduces no-shows by letting guests and hosts add the experience to their calendar in one tap.
- GET /api/bookings/{bookingId}/calendar.ics; only the booking's traveller or host may download
- Uses the booking's scheduled slot; errors if the booking has no scheduled time; defaults to a 1-hour duration when no end time
- RFC 5545-compliant escaping; status CONFIRMED

**Add-to-calendar deep links** — Returns ready-made Google Calendar and Outlook 'add event' deep links plus the .ics path for a booking.
*Business value:* One-click calendar add across the major providers without manual entry.
- GET /api/bookings/{bookingId}/calendar-links returns googleLink, outlookLink, icsPath
- Participant-only access
- A public (no-auth) Google Calendar link is also generated internally for use in booking confirmation messages

### Admin dashboard & metrics

**Platform metrics summary** — A single admin dashboard endpoint returning aggregate counts across the whole platform: users, host profiles (total/pending/approved), experiences (total/pending/approved), bookings (total/requested/accepted/cancelled), open and in-review safety reports, and visible/hidden reviews.
*Business value:* Gives operators an at-a-glance health and workload view for moderation and growth.
- GET /api/admin/dashboard/summary
- Cancelled bookings sums both 'cancelled by user' and 'cancelled by local'
- ADMIN-only

### Admin moderation & review queues

**Host (local profile) application review** — Admins list pending host applications and approve them, reject them with a reason, or send them back to the host with requested changes.
*Business value:* Enforces the curated, verified-host marketplace promise.
- GET /api/admin/local-profiles/pending; POST .../{id}/approve, .../reject, .../request-changes (reject and request-changes carry a moderation reason)
- Approval unlocks the host's ability to publish experiences

**Experience review & moderation** — Admins list experiences awaiting review and approve (making them publicly bookable) or reject them.
*Business value:* Quality gate ensuring only vetted experiences go live.
- GET /api/admin/experiences/pending; POST .../{id}/approve, .../reject

**Review moderation** — Admins view all guest/host reviews and can hide a review from public display or restore (unhide) a previously hidden one, each with a reason.
*Business value:* Removes abusive or policy-violating reviews while keeping an audit reason.
- GET /api/admin/reviews; POST .../{id}/hide and .../{id}/unhide with a reason (<=1000 chars)
- Hiding recomputes the affected rating (per handoff)

**Safety report triage & resolution** — Admins list user-submitted safety reports (optionally filtered by status) and move them through in-review, resolved or dismissed, recording decision details.
*Business value:* Operationalises trust & safety incident handling.
- GET /api/admin/safety/reports?status=; POST .../{id}/mark-in-review, .../resolve, .../dismiss
- Decision request body captures the admin's notes/outcome

**User account management** — Admins list all users, view a single user's administrative details, and change a user's account status (e.g. active/suspended).
*Business value:* Lets operators suspend bad actors and manage accounts.
- GET /api/admin/users, GET .../{userId}, PUT .../{userId}/status

**Payment record oversight** — Admins view all payment records across the platform, optionally filtered by status, and drill into a single payment's full details.
*Business value:* Financial visibility and dispute/audit support for operations.
- GET /api/admin/payments?status=, GET .../{paymentId}
- Read-only oversight

**City pool management** — Admins manage the set of cities experiences can be offered in: list all (including inactive), add a city, and activate or deactivate a city.
*Business value:* Controls geographic expansion; deactivating a city removes it from new-experience selection without affecting existing experiences.
- GET /api/admin/cities; POST .../ (create), .../{id}/activate, .../{id}/deactivate
- Duplicate city names rejected

**Admin booking management** — Admins list bookings (optionally by status), view a booking, create a booking on behalf of users, and cancel or reschedule a booking.
*Business value:* Lets support resolve booking issues directly and provision manual bookings.
- GET /api/admin/bookings?status=, GET .../{id}, POST .../ (create), POST .../{id}/cancel, POST .../{id}/reschedule

### Admin operations (provisioning)

**Create or approve a host profile on behalf of a user** — Admins can directly create (or update and auto-approve) a host profile for an existing LOCAL-role user, filling in display details, languages, host cities/categories, bank/payout details, legal name and address.
*Business value:* Enables hands-on, white-glove host onboarding outside the self-service flow.
- POST /api/admin/ops/local-profiles; user must exist and have LOCAL role
- Sets verification to MANUALLY_APPROVED and approval to APPROVED, stamps reviewed/submitted times, clears prior rejection/changes reasons, records an optional admin note
- Resolves provided city and category IDs (errors if any not found)

**Create a pre-approved experience** — Admins can create an experience that is immediately APPROVED, bypassing the normal submit-and-review flow, for an already-approved host profile.
*Business value:* Lets operators seed or fast-track quality listings.
- POST /api/admin/ops/experiences; host profile must be APPROVED, city must exist, category optional
- Validates duration (>=30 min), non-negative price, 3-letter currency, max guests (>=1); auto-generates a unique slug

### Rate limiting

**Per-IP public API rate limiting** — Throttles unauthenticated/guest endpoints per client IP using a Redis sliding window, returning a 'too many requests' error when the limit is exceeded.
*Business value:* Protects public surfaces from abuse and scraping while keeping the core app available.
- Configurable enable flag, max requests and window seconds (app.rate-limit.public-api.*)
- Applied to guest booking, guest payment, guest check-in, guest no-show, contact-us and public newsletter endpoints
- Client IP resolved from X-Forwarded-For, then X-Real-IP, then remote address
- Fail-open: any Redis/infrastructure failure does NOT block requests

### Cross-cutting platform & config

**Health check** — A public endpoint reporting that the service is up, with service name and timestamp.
*Business value:* Supports uptime monitoring and load-balancer health probes.
- GET /api/health; public, no auth

**Consistent error responses** — A global handler turns validation errors, bad requests, missing resources, malformed JSON, unknown endpoints and unexpected failures into a uniform JSON error shape (timestamp, status, error, message, path).
*Business value:* Predictable, frontend-friendly error handling across every endpoint.
- Maps BadRequest->400, ResourceNotFound->404, validation/constraint->400, unknown route->404, anything else->500 with a generic message

**API documentation & auth scheme** — Interactive OpenAPI/Swagger documentation with a bearer-JWT 'Authorize' button, noting which endpoints are public vs. token-protected.
*Business value:* Accelerates frontend and third-party integration.
- Configured title 'LocalBuddy API' v1.0; /api/public/** and /api/auth/** documented as open

**Admin authorization enforcement** — All admin endpoints are gated to the ADMIN role at the security layer.
*Business value:* Ensures operational and moderation tools are not exposed to ordinary users.
- /api/admin/** and /api/users/** require ADMIN role
- Admin/support roles are restricted at registration

**Dev admin bootstrap** — In the dev profile, automatically ensures a known admin account exists (creating it or promoting/activating an existing one).
*Business value:* Smooths local development and testing without manual seeding.
- Dev profile only; credentials configurable via app.dev-admin.*
- Not active in production profiles

**Async processing & Redis support** — Enables background/asynchronous task execution and provides Redis connectivity used by rate limiting (and other caching/limiting needs).
*Business value:* Keeps user-facing requests fast by offloading work and backs the rate limiter.
- @EnableAsync; StringRedisTemplate bean
- Shared Jackson ObjectMapper bean (java.time modules auto-registered) so services like the AI client serialise correctly

**Geospatial distance helper** — Shared utility computing great-circle (Haversine) distance in metres between two latitude/longitude points.
*Business value:* Powers 'near me' distance and geofenced check-in features platform-wide.
- Used by location/check-in features outside this cluster
