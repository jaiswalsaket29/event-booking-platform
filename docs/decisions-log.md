# Decisions log

One short entry per feature, newest at the bottom. This file is for interview prep, so each entry answers: what was built, why it was built that way, and the main alternative and trade-off. Keep each entry under about 10 lines.

Template:

```
## <Feature> (<date>, <commit>)
- What: ...
- Why this way: ...
- Alternative considered: ... (trade-off: ...)
- Interview hook: one sentence you'd say if asked about it.
```

---

## Refresh tokens (Day 8)
- What: opaque 64-byte random refresh tokens, stored as SHA-256 hashes in `refresh_tokens`, 30-day expiry, rotated on every `/auth/refresh`, revoked on `/auth/logout`.
- Why this way: a DB-backed token makes logout and revocation real, whereas a JWT alone stays valid until it expires. Hashing means a DB leak doesn't hand out usable tokens. Rotation limits how long a stolen token is useful.
- Alternative considered: a long-lived JWT refresh token (trade-off: no server-side revocation) and an httpOnly cookie (trade-off: better XSS protection but needs CSRF handling; possible later upgrade).
- Interview hook: "Access tokens are stateless for speed; refresh tokens are stateful so I can revoke them."

## Global error handling (2026-10-04)
- What: `GlobalExceptionHandler` maps every exception to one `ApiError` body (`timestamp`, `status`, `error`, `message`, `path`, optional `fieldErrors`). The security chain uses a JSON `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) that write the same shape. Bad refresh tokens throw `InvalidRefreshTokenException` (401). Signup/login DTOs now use Bean Validation, and emails are trimmed and lower-cased.
- Why this way: clients (both frontends) can rely on one error contract no matter where a request was rejected. Unknown exceptions are logged and returned as a generic 500, so stack traces never leak.
- Alternative considered: RFC 7807 `ProblemDetail` via `ResponseEntityExceptionHandler` (trade-off: it's a standard, but its shape is less convenient for form field errors and the roadmap specified this body).
- Interview hook: "Errors from the filter chain and from controllers come out in the same JSON shape, because the frontend shouldn't need to know which layer said no."
- Also: integration tests now share one Testcontainers Postgres through a cached Spring context (`support/IntegrationTest`), so `BackendApplicationTests` no longer needs the compose DB or `.env`.

## Seeded admin account (2026-10-04)
- What: Flyway `V6__seed_admin_user.sql` inserts one ADMIN (`admin@eventbooking.dev`, BCrypt hash of a dev password documented in the README), `ON CONFLICT (email) DO NOTHING`.
- Why this way: public signup can never grant ADMIN, so the first admin has to come from outside the API. A migration makes every environment (including Testcontainers) start with a known admin, and the integration tests log in with it.
- Alternative considered: a startup `CommandLineRunner` reading the admin email/password from env (trade-off: no password hash in git, but the account then depends on runtime config; a reasonable prod upgrade).
- Interview hook: "Admins are provisioned, never self-registered; the seed holds only a BCrypt hash, and the dev password is documented as dev-only."

## Current-user endpoint (2026-10-04)
- What: `GET /api/v1/users/me` returns the caller's `UserResponse`, resolved from the UUID principal the JWT filter sets.
- Why this way: the frontends need fresh profile state (especially `emailVerified`, which can change after the access token was issued), so it's read from the DB, not the token claims. A deleted user with a still-valid token gets 404.
- Alternative considered: decoding claims client-side only (trade-off: no round trip, but stale after verification or role changes).
- Interview hook: "The token says who you are; the database says what you look like now."

## Email verification (2026-10-04)
- What: signup issues an `EmailVerificationToken` (32 random bytes, stored as a SHA-256 hash, 24h TTL) and emails a `FRONTEND_USER_URL/verify-email?token=...` link. `POST /auth/verify-email` consumes it (single use); `POST /auth/resend-verification` invalidates outstanding tokens and sends a fresh one. Email goes through an `EmailService` interface; the default `LoggingEmailService` just logs (`@ConditionalOnMissingBean`, so an SMTP bean can replace it).
- Why this way: hashing one-time tokens costs nothing and means a DB read can't verify accounts. Resend always returns the same 200 message so it can't be used to check which emails are registered. Login still works unverified (per design); booking will enforce it.
- Alternative considered: a signed JWT verification link with no DB row (trade-off: stateless, but can't be made single-use or revoked on resend).
- Interview hook: "Every one-time token is random, hashed at rest, single-use, and short-lived; resend invalidates the old one."
- Note: the access token's `emailVerified` claim is stale until the next `/auth/refresh`; `/users/me` reads the live value.

## Password reset (2026-10-04)
- What: `POST /auth/forgot-password` always returns the same 200 message and, only if the account exists, emails a 30-minute single-use link (token hashed at rest, earlier tokens invalidated). `POST /auth/reset-password` sets the new BCrypt hash, burns the token, marks the email verified, and revokes all of the user's refresh tokens.
- Why this way: identical responses prevent account enumeration. Revoking every refresh token means a reset actually evicts an attacker who had a session. Following an emailed link proves ownership of the address, so marking it verified is safe.
- Alternative considered: emailing a temporary password (trade-off: simpler, but the secret sits in an inbox and stays valid; never acceptable).
- Interview hook: "A password reset signs you out everywhere: the reset bulk-revokes refresh tokens in the same transaction."

## CORS allowlist (2026-10-04)
- What: a `CorsConfigurationSource` for `/api/**` allowing exactly the origins in `app.cors.allowed-origins` (default `http://localhost:5173,http://localhost:5174`, overridable with `CORS_ALLOWED_ORIGINS`), wired into the security chain with `http.cors()`.
- Why this way: putting CORS inside Spring Security means preflight `OPTIONS` requests are answered before authentication, so protected routes don't 401 their own preflights. No credentials mode, because tokens travel in headers rather than cookies.
- Alternative considered: `@CrossOrigin` per controller or a `WebMvcConfigurer` mapping (trade-off: runs after the security filters, so preflights to protected routes get rejected).
- Interview hook: "CORS lives in the security filter chain with an explicit allowlist from env, never `*`."

## Google OAuth2 login (2026-10-04)
- What: `spring-boot-starter-security-oauth2-client`, wired into the security chain only when a `ClientRegistrationRepository` exists, i.e. when the `google-oauth` profile is active and `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` are set. `OAuth2LoginSuccessHandler` maps the Google identity through `GoogleAccountService`, issues our own access + refresh tokens, and redirects to `FRONTEND_USER_URL/oauth2/callback#accessToken=...&refreshToken=...`.
- Linking rules: match by Google `sub` first; else link to an existing account by email only if Google reports `email_verified`; else create a USER with no password. Admins are rejected (local login only). `auth_provider` keeps the original value after linking.
- Why this way: the rest of the API only understands our JWT + refresh token, so OAuth terminates in them. Requiring `email_verified` before auto-linking prevents account takeover through an unverified Google email. Off by default, so the app runs without Google credentials.
- Alternative considered: a one-time code in the redirect, exchanged for tokens via POST (trade-off: tokens never touch the URL at all, but needs a short-lived code store; fragments already stay out of server logs and Referer headers, so it's a later hardening step).
- Interview hook: "Google proves identity, but my server still issues the session; and I only auto-link accounts when Google vouches for the email."
- Not verified end to end with real Google credentials; covered by service tests for the linking rules and a wiring test that the authorization endpoint redirects to Google when enabled.

## Locations, halls and bulk seat layout (2026-10-04)
- What: repositories/services/DTOs for `Location`, `Hall`, `Seat`; admin CRUD under `/api/v1/admin/locations` and `/api/v1/admin/halls`; `PUT /admin/halls/{id}/seats` replaces a hall's layout from blocks of `rows x seatsPerRow x seatType` with row labels continuing across blocks (A..Z, AA, AB...). Public `GET /api/v1/locations`, `/locations/cities`, `/locations/{id}`.
- Why this way: hall capacity is derived from the layout instead of typed in, so the two can't disagree. A layout can only be replaced while no session uses the hall: seats are shared reference data and `session_seats` point at them, so the FK refuses it and the service turns that into a 409 with a clear message. Deleting a location or hall that's still referenced is also a 409, not a 500.
- Alternative considered: an explicit "does any session use this hall?" check (trade-off: nicer, but the location domain would have to call the session domain, which already calls the location domain; letting the database enforce it avoids the cycle).
- Interview hook: "Admins describe a venue in three numbers per block and the API generates hundreds of seats; once tickets exist for a hall, the database won't let its seats change."

## Artists and events admin (2026-10-04)
- What: admin CRUD for artists (`/api/v1/admin/artists`, paged with name search) and events (`/api/v1/admin/events`, paged, filter by status, title search via a JPA `Specification`). `PUT /admin/events/{id}/artists` replaces the line-up (with an optional role such as "headliner"); `POST/DELETE /admin/events/{id}/images` manage the gallery. Paged endpoints return a `PageResponse` record; an unknown `sort` property is a 400.
- Why this way: the line-up is edited as a whole list because that's how the admin form works, and it keeps the `EventArtist` join rows trivially consistent. Deleting an event relies on the existing `ON DELETE CASCADE` for images/line-up, while its sessions block it (409, "cancel it instead"). Changing status to CANCELLED doesn't cascade yet; that rule belongs with bookings (Phase 4/5).
- Alternative considered: returning Spring's `Page` directly (trade-off: less code, but `PageImpl`'s JSON isn't a stable contract and Spring warns about it).
- Interview hook: "List endpoints return my own page DTO, so the frontend contract doesn't depend on Spring internals."

## Session creation and seating modes (2026-10-04)
- What: admin session endpoints (`/api/v1/admin/events/{id}/sessions`, `/admin/sessions/{id}`, `/admin/sessions/{id}/tiers`, `/admin/tiers/{id}`, `/admin/sessions/{id}/seats`). `SessionService.create` enforces exactly one mode: ASSIGNED_SEATING (hall from the same location with a non-empty layout, `basePrice`; no pricingMode/capacity/tiers), GA FLAT (`availableCapacity` + `basePrice`), or GA TIERED (at least one uniquely-named tier; `basePrice` set to the cheapest tier). Assigned sessions copy every hall seat into `session_seats` priced `basePrice x multiplier(seatType)` (REGULAR 1.0, PREMIUM 1.5, RECLINER 2.0 in `app.pricing.seat-type-multipliers`, validated at startup by `SeatPriceCalculator`).
- Why this way: the seat price is frozen when the session is created, so later config changes never reprice existing showtimes. Responses expose one `ticketsAvailable` number whatever the mode, computed with two batched queries per list rather than one per session. Tier edits take a `PESSIMISTIC_WRITE` lock and shift `availableCapacity` by the capacity delta, so tickets already sold stay sold; they can't go below what's sold. After creation only times and status can change; venue, mode and prices are fixed (delete and recreate while there are no bookings).
- Alternative considered: computing seat prices at booking time from the current multipliers (trade-off: no snapshot rows to maintain, but a price change would silently reprice a show people are already browsing).
- Interview hook: "The three seating modes are mutually exclusive by validation at creation time, so the booking strategy can trust the shape of the data."
- Known gap: cancelling a session or event only flips its status; cascading to bookings belongs to the booking phase.

## Public catalog endpoints (2026-10-04)
- What: anonymous `GET` endpoints: `/api/v1/events` (paged; filters `city`, `category`, `from`/`to` instants, `q`, `featured`, `artistId`; `sort=DATE|TITLE`), `/events/categories`, `/events/{id}` (details + line-up + gallery + upcoming sessions), `/events/{id}/sessions`, `/sessions/{id}`, `/sessions/{id}/seats` (status + price per seat), `/artists`, `/artists/{id}` (profile + upcoming events), plus the location reads from earlier.
- Why this way: an event is listed only if it's PUBLISHED and has a SCHEDULED session that matches the filters, and it's ordered by that session's start, because date and city live on `Session`, not `Event`. `EventSearchRepository` therefore queries sessions grouped by event with the Criteria API (every filter a bound parameter), then loads the page's events in one query. `nextSessionStart` and `startingPrice` come from the same aggregate. Unpublished events and their sessions return 404, not 403, so drafts don't leak.
- Alternative considered: a denormalised `next_session_start` column on `events` (trade-off: simpler and faster queries, but it needs keeping in sync on every session change and still can't honour a date-range or city filter).
- Interview hook: "The listing is a GROUP BY over sessions, so 'events in Pune next weekend, soonest first' is one SQL query plus one id lookup."
- Note: the event search queries the `Session` entity from the event domain's repository. That's a read-only query, not a call into the session domain's repository, so I kept it there rather than move event listing into the session package.

## Contact form (2026-10-04)
- What: public `POST /api/v1/contact` (validated, stored as NEW), admin `GET /api/v1/admin/contact-messages` (paged, newest first, `?status=` filter) and idempotent `PATCH /admin/contact-messages/{id}/resolve`. `MessageResponse` moved to `common/dto` now that two domains use it.
- Why this way: storing messages instead of only emailing them gives admins a triage queue in the dashboard. Resolve is a PATCH on a sub-resource because it's a single state transition, not a general edit, and repeating it is harmless.
- Alternative considered: forwarding submissions straight to an inbox via `EmailService` (trade-off: no admin UI needed, but no status tracking; could be added on top later).
- Interview hook: "Even the contact form goes through the same validation, error body and role gate as the rest of the API."

## Demo seed data (2026-10-04)
- What: `common/seed/DemoDataSeeder` (an `ApplicationRunner`) builds a fictional catalog in one transaction: 8 venues in 5 cities plus an online venue, 4 halls with seat layouts, 8 artists, 12 published events and 1 draft, with sessions covering assigned seating (theatre, cinema), flat GA (comedy, online workshop) and tiered GA (concerts, arcade passes). Session dates are relative to "today" in IST. It runs only when `app.seed.demo-data=true` (`SEED_DEMO_DATA`, default true; false in tests) and there are no events yet.
- Why this way: going through the real services means the seed obeys the same validation as the admin API, including seat generation and price snapshots, and it can't drift from the schema. Relative dates keep the demo from going stale. The property flag (rather than a `dev` profile) lets the portfolio deployment seed itself too.
- Alternative considered: a Flyway `R__` repeatable SQL migration (trade-off: no Java involved, but hand-written SQL for hundreds of seat rows, fixed dates that expire, and it would also run inside every test database).
- Interview hook: "The demo data is generated by the same code paths an admin uses, so seeding is also a smoke test."
- Verified by booting against the compose Postgres: V6 applied, seeder ran, and `/api/v1/events` plus admin login worked.

## Admin authorization gate test (2026-10-04)
- What: `AdminAuthorizationGateTest` reads every `/api/v1/admin/**` mapping from `RequestMappingHandlerMapping`, fills path variables with random UUIDs, and asserts 401 for anonymous callers and 403 for USER tokens on every method + path. It also asserts each admin controller carries `@PreAuthorize("hasRole('ADMIN')")`. This sits alongside per-feature integration tests (auth, locations/halls, events/artists, sessions, public catalog, contact, seeder).
- Why this way: a hand-written list of URLs goes stale; discovering them means a new admin endpoint is covered the day it's added, and forgetting the annotation fails the build.
- Alternative considered: one 403 assertion per controller test (trade-off: simpler to read, but easy to forget for new endpoints).
- Interview hook: "My test suite enumerates the admin API from Spring's own routing table and proves every route is locked."
