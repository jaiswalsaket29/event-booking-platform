# Phase 2b: Refresh tokens (learning notes)

Backfilled. Phase 2b made sessions long-lived without making access tokens long-lived: opaque, DB-backed, rotating refresh tokens (migration V5).

## 1. Request walkthroughs

### A. Refresh (rotation)

`POST /api/v1/auth/refresh {"refreshToken": "<raw>"}`

1. `auth/controller/AuthRefreshController.refresh` → `RefreshTokenService.validateAndRevoke(raw)`.
2. Hash the raw token with SHA-256 (`SecureTokens.sha256`) and look it up: `RefreshTokenRepository.findByTokenHash` → `SELECT ... FROM refresh_tokens WHERE token_hash = ?` (unique index).
3. Reject with `InvalidRefreshTokenException` (401) if not found, already revoked (a replay), or expired.
4. Mark it `revoked = true` and save.
5. The controller issues a **new** access token (`JwtTokenProvider`) and a **new** refresh token (`RefreshTokenService.issue`: 64 random bytes, store only the hash, 30-day expiry).
6. Response `TokenResponse(accessToken, refreshToken)`. The old refresh token is now useless.

### B. Logout

`POST /api/v1/auth/logout {"refreshToken"}` → `RefreshTokenService.revoke`: if the hash exists, mark it revoked. Always returns 204, even for unknown tokens, so it's idempotent and leaks nothing.

## 2. Key classes

| Class | Role |
|---|---|
| `auth/entity/RefreshToken` | `user`, `tokenHash` (unique), `expiresAt`, `revoked`, `createdAt` |
| `db/migration/V5__add_refresh_tokens.sql` | Table plus indexes on `user_id` and `token_hash` |
| `auth/service/RefreshTokenService` | `issue`, `validateAndRevoke`, `revoke`, and (Phase 2c) `revokeAllForUser` |
| `auth/controller/AuthRefreshController` | `/refresh` and `/logout` |

## 3. Concepts used

- **Short access + long refresh.** The access JWT (15 min) is checked statelessly on every request; the refresh token (30 days) is checked against the DB only when the access token runs out. You get revocation without a DB hit per request.
- **Opaque vs JWT refresh tokens.** The refresh token is a random string with no meaning, so the only way to validate it is the DB. That's exactly what makes logout real.
- **Hashing at rest.** Only SHA-256 of the token is stored. A DB dump doesn't contain usable tokens.
- **Rotation.** Each refresh burns the old token and issues a new one, which shortens how long a stolen token stays useful. Replaying a used token is rejected. Revoking the whole token family on replay (theft detection) is noted as future scope.
- **Idempotent logout.** Repeating it, or sending a junk token, has the same result: 204.
- **Returned in the body, not a cookie.** Simpler for two SPAs and mobile-friendly. The trade-off is XSS exposure (an httpOnly cookie would hide it from JS but brings back CSRF concerns); see `docs/decisions-log.md`.

## 4. Try it yourself

1. Log in, then call `/auth/refresh` twice with the **same** refresh token:
   ```bash
   curl -s -X POST localhost:8080/api/v1/auth/refresh -H "Content-Type: application/json" -d '{"refreshToken":"<token>"}'
   ```
   The first call succeeds; the second gets 401 "already been used or revoked". That's rotation.
2. In psql: `select token_hash, revoked, expires_at from refresh_tokens order by created_at desc limit 3;`. You'll only see hashes, never raw tokens.
3. Comment out `entity.setRevoked(true);` in `validateAndRevoke`, restart the app, and repeat step 1: both calls now succeed, so a stolen token would work for 30 days. Revert. (There is no automated replay test yet; writing one in the style of `PasswordResetTest` is a good exercise.)

## 5. Interview questions

**Q1. Why have refresh tokens at all?**
To keep access tokens short-lived (limiting damage if one leaks) without forcing users to log in every 15 minutes.

**Q2. Why store refresh tokens in the DB when JWTs are stateless?**
Statelessness makes revocation impossible. Refresh is rare (about every 15 minutes per client), so a DB lookup there is cheap, and it makes logout and "sign out everywhere" real.

**Q3. Why hash them? They're random.**
A raw token in the DB is a live credential for whoever reads the DB. SHA-256 is enough because the input has 512 bits of entropy; there's nothing to brute-force.

**Q4. What is refresh token rotation and what does it protect against?**
Each use returns a new token and revokes the old one. A stolen token works at most until the legitimate client refreshes, and a replay is detectable. Full reuse detection would revoke all of that user's tokens on replay; that's future scope here.

**Q5. Why does logout always return 204?**
Idempotency and no information leak: the response doesn't reveal whether the token ever existed.
