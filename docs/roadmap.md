# Roadmap

The goal is to ship a working, deployed, end-to-end product for a portfolio. Order matters (each phase depends on the previous one). Tick boxes as phases land. Each bullet is roughly one commit.

## Done

- [x] **Phase 0: Skeleton.** Monorepo, Spring Boot 4.1 bootstrap, Docker Compose (Postgres on 5433, Redis), `application.yml`.
- [x] **Phase 1: Data model.** Flyway V1–V4, all 16 entities, Testcontainers `UserRepositoryTest`.
- [x] **Phase 2a: Local auth.** BCrypt `PasswordEncoder`, `/api/v1/auth/signup` and `/login`, `JwtTokenProvider`, `JwtAuthenticationFilter`, stateless `SecurityConfig`.
- [x] **Phase 2b: Refresh tokens.** V5 `refresh_tokens`, rotation on `/auth/refresh`, idempotent `/auth/logout`.

## Remaining: backend

### Phase 2c: Auth completion
- [x] Global `@RestControllerAdvice` in `common/exception`: consistent JSON error body (`timestamp`, `status`, `error`, `message`, `path`, field errors for validation). Map `EmailAlreadyExistsException` → 409, `InvalidCredentialsException` / bad refresh token → 401, `AccessDeniedException` → 403, not-found → 404. Return JSON 401/403 from the security chain too (custom `AuthenticationEntryPoint` / `AccessDeniedHandler`). Replace the `IllegalArgumentException`s in `RefreshTokenService` with a proper exception.
- [x] Seed one admin account via a Flyway migration (BCrypt hash of a dev password; document the credentials in the README as dev-only).
- [x] `GET /api/v1/users/me`.
- [x] Email verification: issue `EmailVerificationToken` on signup, `POST /api/v1/auth/verify-email`, `POST /api/v1/auth/resend-verification`. Email sending goes behind an `EmailService` interface; the dev implementation logs the link.
- [x] Password reset: `POST /api/v1/auth/forgot-password` (always 200, no account enumeration), `POST /api/v1/auth/reset-password`. Revoke all refresh tokens on reset.
- [x] CORS allowlist for `http://localhost:5173` and `http://localhost:5174` (configurable via env for prod).
- [x] Delete `common/TestController`.
- [x] Google OAuth2 login (optional; do last in this phase, skip if blocked by needing real Google credentials and leave it behind a config flag).

### Phase 3: Catalog APIs
- [x] Repositories, services, DTOs for Location, Hall, Seat, Artist, Event, EventImage, Session, TicketTier.
- [x] Admin CRUD under `/api/v1/admin/**` (`@PreAuthorize("hasRole('ADMIN')")`). Include a bulk seat-layout endpoint for a hall (rows × seats per row × seat type).
- [x] Session creation validates seating/pricing mode (see `design-decisions.md`), generates `SessionSeat` rows for assigned seating with the price snapshot from a seat-type pricing table (multipliers on `basePrice` are fine: REGULAR 1.0, PREMIUM 1.5, RECLINER 2.0, kept in config).
- [x] Public read endpoints: event list (paged, sortable by date, filter by city/category/date range, published only), event detail with artists + images + upcoming sessions, sessions for an event, seat map for a session (status + price per seat), locations, artists, artist page.
- [x] Contact form `POST /api/v1/contact` (public) + admin list/resolve.
- [x] Dev seed data (Flyway `R__` repeatable migration or a `dev`-profile `CommandLineRunner`): a few cities, venues, halls with seat layouts, artists, ~10 published events with sessions across all three seating modes. Use real-looking but fictional names.
- [x] Integration tests (Testcontainers + MockMvc) for the main endpoints and the admin authorization gate.

### Phase 4: Booking and concurrency
- [x] `SeatingStrategy` interface + three implementations (flat GA, tiered GA, assigned seating) with pessimistic locks.
- [x] `POST /api/v1/bookings` creates a `PENDING` booking and holds capacity/seats. Requires `emailVerified` (403 otherwise). Hold TTL configurable (default 10 min).
- [x] Redis: add `spring-boot-starter-data-redis`; TTL key per hold; keyspace-notification listener that releases expired holds and moves the booking out of `PENDING`. Add a Testcontainers Redis container for tests.
- [x] `GET /api/v1/bookings` (my bookings), `GET /api/v1/bookings/{id}` (owner only).
- [x] Concurrency test: N threads booking the last seat / last capacity → exactly one succeeds.

### Phase 5: Payments
- [x] `PaymentGateway` interface with a simulated implementation (deterministic success/failure via a test card or amount rule). The optional Razorpay test-mode implementation behind a profile is **not built yet** (needs test-mode keys; see the Phase 5 entry in `decisions-log.md`).
- [x] `POST /api/v1/bookings/{id}/payments` with an `Idempotency-Key` header: insert-or-fetch-existing.
- [x] Booking/payment state machine with terminal states enforced in one place.
- [x] Retry cap, explicit idempotent release on failure.
- [x] Webhook endpoint `POST /api/v1/payments/webhook`, idempotent on `transactionId`, signature-checked.
- [x] On `CONFIRMED`: generate `bookingReference`, QR (zxing) at `GET /api/v1/bookings/{id}/qr` (PNG), confirmation email via `EmailService` (`@Async`).
- [x] Admin: bookings list with filters, dashboard stats endpoint (bookings/revenue per day, top events).

### Phase 5b: Hardening (gaps found after Phase 5)
- [x] Cancellation cascade. Cancelling a session (admin `PUT /admin/sessions/{id}` with `CANCELLED`) or an event (`PUT /admin/events/{id}` with `CANCELLED`, which cancels its SCHEDULED sessions) must: end its PENDING bookings as CANCELLED and release their holds; mark its CONFIRMED bookings CANCELLED (seats stay BOOKED as the record of what was sold; flagged as refund owed; refunds stay out of scope); and refuse new holds/payments. Bookings are locked row by row (booking → inventory, as everywhere). Document the rule in `design-decisions.md`.
- [x] Late-payment window. Hold expiry (Redis listener and sweeper) must not fail a booking while a recent PENDING payment is in flight; defer it (the sweeper retries) until the payment settles or a grace period passes (config). Test: expiry during an in-flight payment, then a success webhook → CONFIRMED.
- [x] Turn off open-in-view (`spring.jpa.open-in-view: false`) and fix code that relied on it (e.g. `RefreshTokenService.validateAndRevoke` returning a lazy `User` to `AuthRefreshController`).
- [x] `POST /api/v1/bookings/{id}/cancel`: the owner abandons a PENDING checkout, releasing the hold now (CANCELLED with no attempt, FAILED if attempted). Refused while a payment is PENDING.
- [x] Test refresh-token replay: a rotated (revoked) token is rejected with 401.
- [x] Surface payments stuck in PENDING (no outcome after the hold ended) to admins: dashboard count and an admin bookings filter.
- [x] Bound `SimulatedPaymentGateway`'s in-memory idempotency map (it grows forever in a long-running demo).

### Phase 6: Polish

Decide before building:
- Caching vs. live availability: public event detail and session responses include `ticketsAvailable`, sessions and prices, which change on every booking. Cache only the slow-changing parts (event, line-up, images) or use a very short TTL; don't serve stale "3 left".
- Rate-limit keys: login/forgot-password per IP + per email, booking creation per user. Behind a proxy (Phase 7) the client IP comes from trusted `X-Forwarded-For`.

- [x] Redis cache-aside on the public event list/detail, evicted on admin edits.
- [x] Rate limiting (Redis token bucket) on login, forgot-password, booking creation.
- [x] Image upload: pre-signed R2 URL endpoint behind an `ObjectStorage` interface. If no R2 credentials are configured, fall back to accepting an image URL directly.
- [x] OpenAPI/Swagger UI via springdoc (public in dev).

### Phase 7: Production

Notes from the Phase 5 review:
- Done: timestamp columns converted to `timestamptz` (V10) and the JVM pinned to UTC.
- Behind the platform's proxy, set `server.forward-headers-strategy: native` (Tomcat RemoteIpValve, trusted proxies only) so rate limits see the real client IP; never read `X-Forwarded-For` directly.
- Set `API_DOCS_ENABLED=false` in production (springdoc spec + Swagger UI are public whenever enabled).
- Production must override the seeded admin password and `PAYMENT_WEBHOOK_SECRET` (a prod profile should refuse to start with the dev default).
- [ ] Multi-stage `Dockerfile` for the backend; `application-prod.yml`; all secrets from env.
- [ ] Actuator health endpoint (public), everything else locked down.
- [ ] GitHub Actions: `./mvnw verify` on push/PR; frontend builds.
- [ ] Deploy: backend + managed Postgres + Redis (Render/Railway/Fly), frontends on Vercel/Netlify. Document in the README.

## Remaining: frontends

Vite + React + TypeScript, React Router, TanStack Query, an axios client with access-token refresh on 401. Mobile-friendly.

### frontend-user (dev port 5173)
- [ ] Auth: signup, login, verify-email landing, forgot/reset password, token refresh, logout.
- [ ] Home: featured events, browse by city/category.
- [ ] Event listing with filters + pagination; event detail with gallery, artists, sessions.
- [ ] Booking: session picker → seat map (assigned) or quantity/tier picker (GA) → hold countdown → payment (generate idempotency key once per checkout screen, disable button on click) → confirmation with QR.
- [ ] My bookings, booking detail with QR.
- [ ] Artist page, contact page, 404.

### frontend-admin (dev port 5174, Black Dashboard React look)
- [ ] Admin login.
- [ ] Dashboard with stats charts.
- [ ] CRUD screens: locations, halls + seat-layout builder, artists, events (+ images), sessions (+ tiers).
- [ ] Bookings list, contact messages triage.

## Portfolio finish
- [ ] Root `README.md`: what it is, live demo links, demo credentials, architecture diagram (Mermaid), screenshots, highlighted engineering decisions (pessimistic locking + Redis holds, idempotent payments, refresh-token rotation, strategy pattern, Flyway + Testcontainers), how to run locally.
- [ ] `docs/decisions-log.md` kept up to date per feature.
