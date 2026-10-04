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
