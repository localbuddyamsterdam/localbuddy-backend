# LocalBuddy — Product Overview & UI Design Brief

_A briefing document for prospective UI/UX design partners. Part 1 explains the product; Part 2 is the design scope to quote against; Part 3 gives the technical context._

---

# Part 1 — The Product

## What is LocalBuddy?

LocalBuddy is a marketplace that connects travelers with local hosts who run small, personal group experiences and tours in the cities they're visiting — starting with **Amsterdam**. Travelers discover and book authentic, locally-run activities; everyday people who know their city turn that knowledge into income by hosting. The platform handles the whole journey end to end — discovery, secure payment, messaging, safety, and reviews. Think of it as a trusted, curated layer between "what the guidebooks say" and "what a local friend would actually show you."

## The problem & value proposition

Most travelers want experiences that feel genuine and personal, but they're stuck between impersonal mass tours and the risk of booking something unvetted off social media. Meanwhile, knowledgeable locals who'd love to share their city have no simple, trustworthy way to turn that into a small business — handling bookings, payments, taxes, and safety alone is daunting.

- **For travelers** — a curated catalogue of small-group experiences run by **verified** locals, with transparent pricing, real reviews, secure payment, and built-in safety tools. They can book in seconds — even without an account — and trust the host is real.
- **For local hosts** — a turnkey way to launch and run a hosting business. The platform manages listings, the booking calendar, payments and payouts, invoicing and tax, and messaging, so hosts focus on delivering a great experience instead of back-office work.
- **For the business** — a managed, trust-first marketplace where every host is vetted, every payment flows through a secure system, and an operations team keeps quality, safety, and fairness high as it scales to new cities.

## Who uses it (three audiences, three very different interfaces)

- **Travelers (customers)** — curious visitors (solo, couples, families, friend groups), usually on a **phone**, comparing options, checking photos/reviews/price. They value speed, trust, and clarity, often want to book **without creating an account**, and care about safety and paying in a familiar currency. *Mobile-first, delight-oriented.*
- **Locals (hosts)** — residents with genuine local knowledge turning it into income; from nervous first-timers to power-hosts running many sessions a week. They need guided setup, attractive listings, an availability calendar, booking control, reliable payouts, and reputation protection. A **livelihood tool**, used often and on the go. *Mobile + desktop.*
- **Admin / operations team** — runs the marketplace: vets hosts and experiences, oversees bookings/payments, handles refunds and cancellations fairly, investigates safety reports, steps into conversations, and configures the commercial machinery (promotions, rates, tax, company details) while watching health dashboards. *Dense, information-rich, desktop-first; built for efficiency, not first-time delight.*

## Feature highlights

### For travelers
- **Discover** by city, category, date, and group size (adults/teens/children/infants), with advanced filters (price, duration, host rating, keyword).
- **Rich experience pages** — galleries, full descriptions, what's included/excluded, meeting point, duration, minimum age, and genuine past-guest reviews.
- **Book in a few taps** — pick a slot, choose group size, add a note, pay in one smooth flow.
- **Private whole-slot buyout** — reserve an experience privately for your own group at a flat price, where the host allows it.
- **Guest checkout, no account** — book with name/email/phone (with terms + consent capture); later look up status by reference number + email.
- **Secure, familiar payment** via a trusted hosted checkout (cards, Apple Pay, Google Pay, and more); confirmation handled automatically.
- **Wishlist / favourites** — tap a heart to save; saved items follow the traveler once they sign in.
- **Waitlist** — get notified if a spot opens on a full experience.
- **Promo codes, referrals, gift cards.**
- **In-app messaging** with hosts before and after booking.
- **Notifications & reminders** (in-app), with email/SMS preferences.
- **Wallet passes & calendar** — add a booking to Apple/Google Wallet and to a calendar.
- **Trip safety** — pre-trip safety checklist, check-in/out, emergency contact, and an **SOS** that alerts support with location.
- **Reviews** after a completed experience.
- **Privacy & consent** built in — versioned consent prompts, data export, account-deletion request.
- **Currency display** — see prices converted to a preferred currency (display only; see note in Part 2).

### For hosts
- **Guided onboarding & verification** — step-by-step from profile to approval, with clear status/messages, including identity and tax details.
- **Create & manage experiences** — descriptions, highlights, pricing, group size, duration, meeting points; submit for approval; edit over time.
- **Photo galleries** — upload, set a cover, reorder, caption.
- **Availability calendar** — open slots, set capacity, block/remove.
- **Booking control** — accept/decline, reschedule, and cancel slots that don't hit a minimum group size, within clear fairness rules.
- **Flexible pricing** — per-person price, private whole-group buyout, and tax-handling input mode.
- **Earnings & payouts** — total earned, available and on-hold balances, payouts to a connected bank account (via Stripe Connect).
- **Invoices & tax** — auto-generated documents; platform handles the commercial detail.
- **Messaging** with guests, plus quick contact.
- **Reliability reputation** — ratings, reviews, and a track record.

### For the admin / operations team
- **Moderate** hosts, experiences, and reviews (approve / reject / request changes / hide).
- **Oversee** all bookings and payments; create bookings manually on a customer's behalf.
- **Refunds & cancellation policies** — cancel with refunds and configure the refund-rule tiers.
- **No-show review** — investigate host/guest no-show claims and approve/reject refunds.
- **Messaging takeover & monitoring** — read any thread, step in transparently, open private side-channels.
- **Trust & safety** — handle safety reports, respond to live SOS alerts, restrict users (block bookings/hosting/accounts).
- **Promotions & commerce** — promo codes, deals, referrals, gift cards.
- **Rates & company config** — commission, service fees, VAT/tax, and the company's invoice/legal details.
- **Dashboard** — platform-health metrics.
- **AI assist** — help hosts write listings, suggest traveler itineraries, and auto-flag inappropriate content for review.

---

# Part 2 — UI Design Brief (scope to quote against)

## ⚠️ Open decisions we need to confirm with you before a firm quote

1. **Platform targets — the single biggest scoping input.** Is this **responsive web only**, web **+ native iOS/Android**, or a **PWA**? If native, **React Native / Flutter vs fully native**? The product leans mobile (Apple/Google Pay, wallet passes), but the host and admin surfaces are desktop-heavy. *We'd like your input on the best fit.*
2. **Brand maturity** — do we apply an existing brand (logo, palette, type, tone) or do you **build the design system from scratch**? Is **dark mode** required?
3. **Out-of-scope / backend-pending** — a **map view** of results and an **admin category-management** screen are *not currently backed by the API* (no gelocation data; categories are read-only). Treat as dropped or contingent on backend work — please don't price them as live features.

## Scope at a glance

- **Three front-end surfaces, one shared design system:** Traveler app (mobile-first), Host console (mobile + desktop), Admin console (desktop, dense).
- **Roughly 140+ distinct screens/states** before per-screen state variations (≈ **70 traveler · 36 host · 39 admin**).
- **State matrix multiplier:** most lists/forms/async screens need **loading, empty, error, rate-limited, session-expired, partial-success, and success** states — design these as a multiplier, not one comp per screen.

## A. Traveler app (≈70 screens)

**Discovery & marketing:** home/landing with hero search + city picker + category tiles + live-deals strip; search entry/overlay; search results (paginated cards, hearts, deal badges, currency); advanced filters panel; no-results state; category landing; city landing; deals listing. _(Map view — backend-pending, see open decisions.)_

**Experience & host:** experience detail (gallery, descriptions, inclusions/exclusions, meeting/end point, duration, min age, safety notes, price incl. private price, host mini-profile, reviews, favourite, "message host"); photo lightbox; experience reviews list; host public profile (note: must **not** render host bank/legal/KYC fields); host reviews list.

**Booking & checkout:** availability/slot picker (capacity, "private available" flag, "join waitlist" when full); booking configurator (guest-count + **age-band steppers with a combined max of 10**, min-age gate, **private-buyout toggle** showing flat price + "blocks the whole slot", traveler note, promo/referral/gift-card fields, price summary with discount lines); promo/referral applied-or-invalid inline states; gift-card redeem + balance; **forced login/signup interstitial** (when a logged-out user hits a login-only action); **guest checkout** (name/email/phone + **required terms & versioned consent**); order review/confirm (final price + **cancellation-policy summary**); Stripe redirect interstitial; payment return/processing (poll status); payment success/ticket-issued; payment failed/retry; rate-limited ("try again shortly"); booking confirmation with reference.

**My account & bookings:** my bookings (status tabs across the full lifecycle incl. **awaiting host acceptance** and **payment expired**); booking detail (status, slot, guests/bands, price + discount + refund state, private indicator, contact); add-to-wallet panel (incl. "not configured" fallback); add-to-calendar panel (Google/Outlook/ICS); cancel-booking modal (reason + **refund preview: 100% if ≥24h, else 0%**); host-reschedule notice; **booking safety checklist** (4 mandatory acknowledgements); in-trip safety panel (check-in/out/**SOS**, traveler-only); emergency contact form (with empty state); leave-a-review (gated to **host-marked-completed** bookings); my reviews; reviews about me; **no-show refund-claim** report (within 48h); my no-show reports; file-a-safety-report.

**Lists, social, messaging:** wishlist page (cards with experience-status badge; empty state); **wishlist post-login merge confirmation** (see journey 4); waitlist join modal (logged-in + guest variant with name/email/phone); my waitlist (status, "notified ≠ reserved" messaging, leave); messages list (unread badges); message thread (bubbles rendering the sender label **verbatim**, including `Admin (Name)` for admin messages); start-conversation entry; WhatsApp click-to-chat handoff (with "configured" fallback).

**Notifications & identity:** notifications feed/bell (read/unread, mark-one/all, deep-links); notification preferences (booking reminders, marketing emails, **email + SMS toggles only**; saved as a full set); login; signup (note: signup returns no session — chain to login); social login (Google/Facebook/Apple); **session-expired/re-auth modal** (access tokens are short-lived — see Part 3); consent gating (5 consent types, version-stamped); account/profile (edit name + phone, verified indicators) + a lightweight "current user" header chip; GDPR export (rendered + downloadable); GDPR delete/anonymize request + my-requests.

**Commerce & utility:** gift-card purchase (amount, recipient, message); my gift cards; public gift-card balance check; referral / invite-a-friend; invoices/receipts + PDF; currency switcher + converter (single-amount and whole-page rates); contact-us (with success + rate-limited states); AI itinerary planner (logged-in; with a "not available" state when AI is off).

## B. Host console (≈36 screens)

**Onboarding & profile:** multi-step onboarding wizard (profile, photo, cities/categories/languages, motivation, KYC legal name + address, banking) driven by a status state-machine; onboarding status banner (Draft → Submitted → Approved / Changes-Requested / Rejected, with messages); submit-for-review confirmation; changes-requested/rejected notice (shows reason, re-edit path); profile view/edit (with "editing an approved profile re-submits it" warning); verification status panel; tax-info form (VAT, tax country, entity type, TIN, business reg, DOB); host consent gating.

**Experiences:** my experiences list (status badges); experience create/edit wizard — basics, content, **pricing (modes: shared / private-allowed / private-only; per-person price; private flat price with `private ≤ maxGuests × per-person` validation; gross/net input mode showing computed net; currency; max guests 1–10)**, **external-listing type (none / own-website-social / aggregator — aggregator forces shared mode/blocks private)**, review & submit; inline pricing/validation helper; photo manager (upload + external URL, captions, cover, drag-reorder, delete, lightbox); AI listing assistant (with "not available" state); submit-for-review confirmation.

**Availability & bookings:** availability calendar (month/week, capacity/booked/remaining); create-slot modal; block/unblock (with "rejected if active bookings"); delete-slot (with guard); cancel-underbooked-slot flow (reason); booking-requests inbox; accept / decline modals (note); reschedule modal; host cancel modal (reason; **blocked within 24h of start**; 100% refund notice); booking detail (traveler/guest info, bands, price + payout split, private flag, contact incl. WhatsApp); mark-completed (enables reviews); host→traveler review (**non-guest bookings only**).

**Money:** earnings dashboard (earned / paid-out / available / on-hold); payouts history; Stripe Connect onboarding entry + return; host invoices (commission / service-fee / payout statements + PDF).

**Comms & safety:** messages (shared component); notifications + preferences; host no-show reporting (informational, within 48h) + my reports; host safety report + in-trip events view (host can **view** but not trigger check-in/SOS); account/profile + GDPR (shared).

## C. Admin console (≈39 screens)

**Overview:** dashboard with KPI tiles (users; host profiles total/pending/approved; experiences total/pending/approved; bookings total/requested/accepted/cancelled; open + in-review safety reports; visible/hidden reviews).

**Moderation:** host applications queue; host application detail + decision (approve / reject-with-reason / request-changes-with-reason; shows KYC + verification); create-host-on-behalf (full onboarding form inside admin); experience moderation queue; experience moderation detail + decision; create-approved-experience-on-behalf (full experience form inside admin).

**Bookings & payments:** bookings oversight (status filter); booking detail (cancel w/ refund, reschedule); manual booking-on-behalf (**offline payment, confirmed — no Stripe flow, needs its own confirmation UI**); payments oversight + detail (fee/payout split, refund states); payouts oversight; run-host-payout (aggregate+transfer) + mark-paid-manual modal; invoices oversight + PDF.

**Policy & config:** refund/cancellation-policy editor (tiers by actor × hours-before-start × refund %; the traveler refund preview is **data-driven from these**); rates editors — **commission, service-fee, VAT** (scoped by platform/city/category/host/experience, with effective-date ranges) + a **rate-change audit log** (dense, validation-heavy forms); company/invoice-issuer settings (legal/VAT/address/IBAN, invoice prefix + footer); cities management (create/activate/deactivate). _(Category management — backend-pending.)_

**Reviews, safety & trust:** reviews moderation (hide/unhide w/ reason); **two separate safety-report queues** — a general "safety" queue and a booking-scoped "trust & safety" queue, each with **different report types, severities, and status sets** (two distinct components); user restrictions (suspend / block-booking / block-hosting, with reason; activate/deactivate); SOS / trip-safety monitor (resolve open SOS); no-show review queue (approve → full refund / reject; remove-flag); reliability view (per host / experience / user: cancellations, no-shows, completed).

**Conversations:** conversation monitor (all threads); conversation detail + reply/take-over (admin message shows to all as `Admin (Name)`); start admin private side-chat with one customer or host.

**Growth & users:** deals manager (CRUD; type, discount, scope/target, window, priority, badge); promo-codes manager (CRUD; limits, redemptions, window); gift-cards admin (list, cancel); users management (list/detail/create/set-status-with-reason); GDPR requests queue (process = anonymize / reject, with note); WhatsApp send tool (with "configured" gate); role-gated admin shell (403 handling).

## Key user journeys (design the happy paths + edge states for these)

1. **Guest discovers → books → pays → gets ticket** (search → detail → slot → guest checkout w/ consent → reference issued → Stripe → return-poll → success → wallet/calendar). Include rate-limit handling.
2. **Member books a shared seat with discounts** (promo + referral + gift-card live validation → confirm → Stripe → confirmation in My Bookings).
3. **Private whole-slot buyout** (private-available slot → enable private toggle, flat price, "blocks all seats" → checkout). Aggregator-listed experiences hide the private option.
4. **Logged-out favouriting → forced login → merge** (heart while logged out **stores ids locally, calls no API** → forced login → merge saved favourites in one call → confirmation toast → hearts rendered from the saved-ids list). *High-risk detail — design the full flow.*
5. **Sold-out slot → waitlist → notified** ("notified ≠ reservation; first-come-first-served").
6. **Traveler cancels and gets a refund** (reason → refund preview 100%/0% by 24h rule → confirm).
7. **Customer ↔ host conversation, then admin takeover** (admin reply appears to both as `Admin (Name)`; admin opens a private side-chat).
8. **Completed trip → review** (host marks completed → traveler review prompt appears → host reviews back, **only for non-guest bookings**; one review per direction).
9. **In-trip safety / SOS** (pre-trip checklist → check-in → SOS with location → support alerted → admin resolves → check-out).
10. **No-show refund claim → admin review** (within 48h, customer reports host no-show as a claim → admin approves → full refund; guest does it via reference + email; host→customer reports are informational only).
11. **Host onboards → approved → lists → booked → paid** (profile + KYC + banking → submit → admin approves → create experience → submit → approve → set availability → accept booking → mark completed → earnings accrue → Connect onboarding → admin payout → invoice).
12. **Host handles a problem booking** (cancel-underbooked, reschedule, or cancel — blocked within 24h of start).
13. **Admin reviews a host application** (request changes → host resubmits → approve).
14. **Admin configures economics** (commission/service-fee/VAT rates, refund-policy tiers, company invoice details; audit log captures changes).
15. **Admin trust & safety action** (report → in-review → user restriction enforced → resolve).
16. **Account lifecycle & GDPR** (signup → versioned consents → use → export data → delete request → admin processes; short sessions force re-auth).
17. **Buy & redeem a gift card** (purchase → recipient checks balance by code → redeem against a booking → depleted at zero).

## Shared / reusable components (build once, use everywhere)

Experience card · rating stars (display + input) · price/currency display (gross vs net, per-person vs flat private, discount, multi-currency switch) · date/availability slot picker · guest-count/age-band stepper (combined max 10, min-age gate) · private-buyout toggle · promo/referral/gift-card input (incl. "invalid but not an error" state) · chat bubble (renders sender label verbatim, incl. `Admin (Name)`) + conversation row with unread badge · notification bell + feed · **status badges** (booking, experience, host-approval/verification, payout, waitlist, gift-card, no-show, two safety status sets — consistent color semantics) · money/refund-state indicator · wishlist heart (logged-out local-store behavior) · Stripe redirect/return handler · add-to-wallet/calendar block (with not-configured fallbacks) · multi-step wizard frame (progress + **save-draft**, because sessions are short) · photo uploader/gallery manager · consent/terms block (versioned; guest/member/host variants) · admin moderation decision panel (approve/reject/request-changes/hide/dismiss + reason) · filter/sort panel · **standardized empty / loading / error / rate-limited / session-expired states** · confirmation/destructive-action modal (reason-required) · admin data table (sortable/filterable/paginated — reused across users, bookings, payments, payouts, both safety queues, deals, promos) · KPI/stat tile · WhatsApp click-to-chat button.

## Cross-cutting requirements

- **Localization** — design for **English + Dutch** at minimum (Amsterdam launch), with length-tolerant layouts and locale-aware date/time and currency formatting. **Currency conversion is display-only** — settlement is in each experience's own currency.
- **Accessibility** — target **WCAG 2.2 AA**: tap-target sizing, contrast on all status badges, focus order in wizards, and accessible **SOS/safety** flows.
- **Transactional templates** — ~21 notification types across email / SMS / WhatsApp / in-app (booking lifecycle, waitlist-available, profile decisions, invoice issued, safety). **Email/SMS template design is a separate deliverable.**
- **External surfaces (not designed by the vendor)** — Stripe Checkout and Stripe Connect onboarding are Stripe-rendered; only our pre-redirect, return/processing, and success/fail screens are in scope. Wallet-pass **artwork** (Apple `.pkpass` / Google) is a separate asset.
- **Content/copy** — much copy is load-bearing and legal/safety-sensitive (consent versions, liability acknowledgement, cancellation policy, "notified ≠ reserved", SOS). Confirm who authors copy and in which languages; consent text is **version-stamped**.

## What we'd like from the design partner (deliverables to quote)

- **Platform & breakpoint matrix** confirming the surfaces above and per-surface breakpoints.
- **Screen & state-count baseline** — a confirmed tally against this inventory (~140+ screens) with the state matrix priced as a multiplier.
- **Fidelity ladder** — low-fi wireframes for all screens; hi-fi comps for the priority journeys (1–4, 8, 9, 11) + component-driven coverage for the rest; interactive prototype for guest checkout and host onboarding; edge-state designs.
- **Design system / component library** — full tokens (color incl. status semantics, type, spacing, elevation), light/dark (confirm), and the shared component set above; state whether from scratch or on an existing brand.
- **Brand & art direction** — logo usage, experience-cover photography/crop rules across breakpoints, iconography, empty/error/SOS illustration, and wallet-pass artwork.
- **Handoff** — **Figma** (dev-mode), components/variants named to match the product's data (status enums etc.), documented tokens, redline/spec convention, exportable assets, and a maintained per-screen state matrix.
- **Accessibility** — committed WCAG 2.2 AA conformance.
- **Localization** — EN + Dutch designs, expandable layouts, locale-aware formatting; confirm copy delivery responsibility.
- **Transactional message templates** — email/SMS (and WhatsApp where used) for the notification set, plus in-app rows with deep links.
- **Admin/host depth** — explicit pricing for the dense admin data-table system, moderation decision panel, the rates editors (+ audit), and the cancellation-refund policy editor.
- **Out-of-scope confirmation** — map view and category-management flagged dropped or backend-pending.
- **Prototype & testing rounds** — whether clickable prototypes/usability testing are included (recommended for guest checkout, host onboarding, and the SOS flow) and how many revision rounds.
- **Design-QA support through build**, and a commitment to keep the Figma library in sync as the API evolves.

---

# Part 3 — Technical context for the design partner

- **The backend is already built and running.** It is a REST API; the full, machine-readable contract is published as **OpenAPI 3** (`docs/openapi.json`, ~200 endpoints) and a human-readable map exists in `docs/FRONTEND_HANDOFF.md`. The frontend team should **generate a typed API client** from the OpenAPI spec and regenerate it on each backend release — that guarantees no feature is missed and breaking changes surface immediately.
- **Auth** is JWT bearer tokens. **Access tokens are short-lived (~15 minutes) and there is no refresh token yet**, so the UI must handle frequent re-authentication gracefully — in particular, **long multi-step forms (host onboarding, experience create) must support save-draft** so work isn't lost if the session expires mid-flow.
- **Payments** run through **Stripe hosted checkout** (the card form is Stripe's page, not ours) and **Stripe Connect** for host payouts. Payment status updates arrive asynchronously, so post-payment screens must **re-fetch and poll** status rather than assume success on redirect.
- **Guests** (no account) are identified by **booking reference + email** across booking, payment, and no-show lookups.
- **Three roles** drive access: traveler (logged-in customer), host (local), and admin. Public browsing needs no login; favouriting, booking-as-member, messaging, and all host/admin features require it (a logged-out user hitting those gets a clean "please log in", which is exactly how the forced-login favouriting flow works).
- **Rate limiting** applies to public/guest actions — design "try again shortly" states for guest booking, payment, no-show, and contact forms.
