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
