# Design decisions

Settled decisions and their reasoning. Follow these when implementing; if a task seems to require breaking one, stop and flag it instead of silently deviating. Field-level detail lives in `entity-reference.md`.

## What this is

A production-grade event ticket booking platform (concerts, movies, comedy, theatre, festivals, tiered-pricing venues like game parlors) modeled loosely on insider.in (user UI) and Black Dashboard React (admin UI). One Spring Boot backend, two React frontends, monorepo:

```
event-booking-platform/
  backend/          Spring Boot 4.1, Java 25, Postgres 16, Redis 7, Flyway
  frontend-user/    React
  frontend-admin/   React (Black Dashboard React base)
  docs/
```

Role-based access (`@PreAuthorize`) enforces the admin/user split; `/api/v1/admin/**` requires `ROLE_ADMIN`.

## Architecture

- **Layered:** Controller → Service → Repository. DTOs (Java records) at the controller boundary; never return entities.
- **Package-by-feature:** `auth`, `user`, `event`, `location`, `session`, `booking`, `payment`, `contact`, plus `common` for genuinely cross-cutting code (security, global exception handling). Inside a domain: `entity/`, `enums/`, `dto/`, `repository/`, `service/`, `controller/`, `exception/` as needed.
- **Boundary rule:** a domain may call another domain's *service*, never its repository.
- **API versioning:** `/api/v1` declared per controller via `@RequestMapping`, not `server.servlet.context-path`.
- **Spring Boot 4 starter names:** `spring-boot-starter-webmvc` (not `-web`), explicit `spring-boot-starter-flyway`. Older tutorials use Boot 3 names.

## Persistence

- **UUID PKs** via `GenerationType.UUID` — prevents IDOR, allows ID before commit, avoids collisions if split.
- **`EnumType.STRING`** always.
- **`FetchType.LAZY`** on every `@ManyToOne`/`@OneToOne`; use `JOIN FETCH` / entity graphs per query.
- **Unidirectional by default** (`@ManyToOne` only); add the inverse side only for a concrete recurring need. E.g. halls per location → `hallRepository.findByLocationId`, not `Location.halls`.
- **Explicit join entities** (`EventArtist`, `BookingSeat`), never bare `@ManyToMany`.
- **Flyway owns the schema** (`ddl-auto: validate`). New change = new `V<n>__*.sql`. Never edit an applied migration.
- **Timestamps** are `Instant`, set by the service layer. Tests run with `-Duser.timezone=UTC`.
- No `@Data` on entities; `@Getter @Setter @NoArgsConstructor @AllArgsConstructor`.

## Domain modeling

- Location/time live on `Session`, not `Event` — one event can run at multiple venues/dates.
- `Hall`/`Seat` are static reference data; `SessionSeat` is the per-showtime row (status, price snapshot, `lockedAt`) that gets locked. Locking one showtime never blocks another.
- GA vs assigned seating is mutually exclusive per session (`Session.seatingType`). GA uses either flat `Session.availableCapacity` or `TicketTier` rows (`Session.pricingMode`), never both.
- `TicketTier` ≠ seat-category pricing. Recliner vs regular in assigned seating = `Seat.seatType` + `SessionSeat.price`.
- `SessionSeat.price` is computed from `Seat.seatType` against a pricing table **when the session is created** (admin flow), and never changes retroactively.
- A multi-city tour = separate `Event` per city, linked by the same `Artist`. `Session` is for genuine repeats at one venue.
- `Booking.bookingReference` is null until `PENDING → CONFIRMED` (same transaction as `Payment.SUCCESS`). Must be high-entropy or HMAC-signed (it's what the QR encodes).
- `BookingSeat` rows are append-only (unique `(booking_id, session_seat_id)`); preventing two active bookings on one seat is the service + row-locking layer's job.
- `Payment` is `@ManyToOne` to `Booking` — multiple attempts per booking.
- `PasswordResetToken` and `EmailVerificationToken` are separate classes, no `@MappedSuperclass`.
- `contact_messages.status` has DB default `NEW` (deliberate minor inconsistency).
- **Cancellation cascade (Phase 5b):** cancelling a session (or an event, which cancels its SCHEDULED sessions; COMPLETED ones are untouched) runs in one transaction via synchronous domain events (`EventCancelledEvent` → `SessionCancelledEvent` → booking listener). Each affected booking is locked in id order: PENDING → CANCELLED with its hold released; CONFIRMED → CANCELLED with seats left BOOKED, the customer emailed, and its successful payment counted as a refund owed (refunds themselves stay out of scope). Holds and payments require a SCHEDULED session, and a payment that lands on a cancelled session is never confirmed. Cancelled sessions and events can't be reopened; completed sessions can't be cancelled.

## Auth (Phase 2)

- Login identifier is email. Passwords BCrypt-hashed. `passwordHash` never serialized.
- Public signup always assigns `Role.USER` server-side. Admins are seeded (Flyway seed or manual insert), local login only — no OAuth for admins.
- **Access token:** short-lived JWT (default 15 min, HS256) with `userId` (subject), `email`, `role`, `emailVerified`. Principal in the security context is the user's `UUID`.
- **Refresh token (decided Day 8):** opaque 64-byte random string, stored only as a SHA-256 hash in `refresh_tokens`, 30-day expiry. `/auth/refresh` rotates (revoke old, issue new pair). Replay of a revoked token is rejected; revoking the whole token family on replay is future scope. `/auth/logout` revokes idempotently. Returned in the response body (not a cookie).
- **Email verification:** login succeeds regardless of `emailVerified`. Booking creation rejects with **403** if `emailVerified` is false. LOCAL signups start unverified; Google signups start verified.
- **Google OAuth (planned):** `spring-boot-starter-oauth2-client`; must terminate in our own JWT + refresh token. Auto-link to an existing account only when Google reports `email_verified: true`. Once linked, `password_hash` and `provider_id` coexist; `auth_provider` is informational.
- **Password reset:** high-entropy, short-lived (15–30 min), single-use `PasswordResetToken`.

## Booking concurrency (Phase 4)

- Strategy pattern: one `SeatingStrategy` interface, three implementations — flat GA (`Session.availableCapacity`), tiered GA (`TicketTier.availableCapacity`), assigned seating (`SessionSeat`). The booking service picks by `seatingType`/`pricingMode`; no category-based branching.
- **Pessimistic locking** (`SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`) on the capacity/seat rows during reservation.
- **Seat holds:** Redis TTL key per hold; `SessionSeat.status = LOCKED` + `lockedAt` mirror it. Abandoned checkouts are released on expiry.
- Must be proven with a Testcontainers test firing concurrent bookings at the same seat/capacity — no overselling.

## Payments (Phase 5)

- Simulated gateway (or Razorpay test mode). Webhook is the source of truth; handler idempotent on `transactionId`.
- **Idempotency key is client-generated**, tied to the checkout screen's lifetime (not per click). Server does insert-or-fetch-existing: on unique-constraint conflict, return the existing `Payment`'s state. Frontend also disables the button on first click.
- **State machine:** Payment `PENDING → SUCCESS | FAILED` (terminal). Booking `PENDING → CONFIRMED` (payment success), `→ FAILED` (retries exhausted, or timeout after an attempt), `→ CANCELLED` (timeout with no attempt, or user/organiser cancellation); `CONFIRMED → CANCELLED` only when the organiser cancels the show. Nothing moves backward.
- Retry = new `Payment` row with its own key, capped (config, default 3), only while the Redis hold is alive. Retry count is derived from `Payment` rows (no counter column).
- On a known terminal failure, release seats/capacity explicitly in the same transaction as `Booking.FAILED`; release must be idempotent (races with TTL expiry).
- Stuck `PENDING` bookings auto-resolve when the hold key expires (Redis keyspace notifications preferred over polling).
- **Payment grace (Phase 5b):** expiry does not end a booking while a payment opened within `app.booking.payment-grace` (2m) is still `PENDING`; the sweeper retries after. A hold can therefore overrun its TTL by at most the grace plus one sweep interval, in exchange for never failing a booking whose payment is about to succeed.
- **QR code:** zxing, generated at `CONFIRMED`, encodes `bookingReference`, regenerated on demand (not stored). One QR per booking.

## Security checklist

- CORS: explicit allowlist of the two frontend origins, never `*`.
- `@Valid` + Bean Validation on every request DTO.
- Global `@RestControllerAdvice`: consistent error body, no stack traces.
- Rate limiting (Redis token bucket) on login, password-reset request, booking attempts.
- Never concatenate user input into JPQL/native queries.
- Secrets via env vars / `.env` (gitignored); never committed.

## Media

- Object storage (Cloudflare R2) with pre-signed upload URLs — backend never streams file bytes. Validate content type (`image/jpeg|png|webp`) and size before issuing the URL.
- CDN-transform service for resizing; no extra DB fields beyond the existing `imageUrl` columns.

## Explicitly out of scope

Sliding-window occupancy tracking, parallel multi-stage festivals, subscriptions/season passes, phone/OTP login, reviews/ratings, coupons, refunds as a first-class entity, admin audit log, wishlist, multi-currency (INR only), structured cancellation policy, mixed-tier single-checkout bookings, per-seat QR codes. Don't build these unless asked.
