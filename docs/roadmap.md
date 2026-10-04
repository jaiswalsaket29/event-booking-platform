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
- [ ] Password reset: `POST /api/v1/auth/forgot-password` (always 200, no account enumeration), `POST /api/v1/auth/reset-password`. Revoke all refresh tokens on reset.
- [ ] CORS allowlist for `http://localhost:5173` and `http://localhost:5174` (configurable via env for prod).
- [ ] Delete `common/TestController`.
- [ ] Google OAuth2 login (optional; do last in this phase, skip if blocked by needing real Google credentials and leave it behind a config flag).

### Phase 3: Catalog APIs
- [ ] Repositories, services, DTOs for Location, Hall, Seat, Artist, Event, EventImage, Session, TicketTier.
- [ ] Admin CRUD under `/api/v1/admin/**` (`@PreAuthorize("hasRole('ADMIN')")`). Include a bulk seat-layout endpoint for a hall (rows × seats per row × seat type).
- [ ] Session creation validates seating/pricing mode (see `design-decisions.md`), generates `SessionSeat` rows for assigned seating with the price snapshot from a seat-type pricing table (multipliers on `basePrice` are fine: REGULAR 1.0, PREMIUM 1.5, RECLINER 2.0, kept in config).
- [ ] Public read endpoints: event list (paged, sortable by date, filter by city/category/date range, published only), event detail with artists + images + upcoming sessions, sessions for an event, seat map for a session (status + price per seat), locations, artists, artist page.
- [ ] Contact form `POST /api/v1/contact` (public) + admin list/resolve.
- [ ] Dev seed data (Flyway `R__` repeatable migration or a `dev`-profile `CommandLineRunner`): a few cities, venues, halls with seat layouts, artists, ~10 published events with sessions across all three seating modes. Use real-looking but fictional names.
- [ ] Integration tests (Testcontainers + MockMvc) for the main endpoints and the admin authorization gate.

### Phase 4: Booking and concurrency
- [ ] `SeatingStrategy` interface + three implementations (flat GA, tiered GA, assigned seating) with pessimistic locks.
- [ ] `POST /api/v1/bookings` creates a `PENDING` booking and holds capacity/seats. Requires `emailVerified` (403 otherwise). Hold TTL configurable (default 10 min).
- [ ] Redis: add `spring-boot-starter-data-redis`; TTL key per hold; keyspace-notification listener that releases expired holds and moves the booking out of `PENDING`. Add a Testcontainers Redis container for tests.
- [ ] `GET /api/v1/bookings` (my bookings), `GET /api/v1/bookings/{id}` (owner only).
- [ ] Concurrency test: N threads booking the last seat / last capacity → exactly one succeeds.

### Phase 5: Payments
- [ ] `PaymentGateway` interface with a simulated implementation (deterministic success/failure via a test card or amount rule). Optional Razorpay test-mode implementation behind a profile.
- [ ] `POST /api/v1/bookings/{id}/payments` with an `Idempotency-Key` header: insert-or-fetch-existing.
- [ ] Booking/payment state machine with terminal states enforced in one place.
- [ ] Retry cap, explicit idempotent release on failure.
- [ ] Webhook endpoint `POST /api/v1/payments/webhook`, idempotent on `transactionId`, signature-checked.
- [ ] On `CONFIRMED`: generate `bookingReference`, QR (zxing) at `GET /api/v1/bookings/{id}/qr` (PNG), confirmation email via `EmailService` (`@Async`).
- [ ] Admin: bookings list with filters, dashboard stats endpoint (bookings/revenue per day, top events).

### Phase 6: Polish
- [ ] Redis cache-aside on the public event list/detail, evicted on admin edits.
- [ ] Rate limiting (Redis token bucket) on login, forgot-password, booking creation.
- [ ] Image upload: pre-signed R2 URL endpoint behind an `ObjectStorage` interface. If no R2 credentials are configured, fall back to accepting an image URL directly.
- [ ] OpenAPI/Swagger UI via springdoc (public in dev).

### Phase 7: Production
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
