# Phase 2c: Auth completion (learning notes)

Phase 2c finished authentication: one error format everywhere, a seeded admin, `/users/me`, email verification, password reset, CORS, and optional Google login. Code is under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. Signup → verification email → verify

1. `POST /api/v1/auth/signup` → `auth/controller/AuthController.signup`. `@Valid SignupRequest` (`@Email`, password 8–72 chars, since BCrypt only reads 72 bytes).
2. `auth/service/AuthService.signup` (`@Transactional`): normalizes the email (`UserService.normalizeEmail`: trim + lower-case), checks uniqueness (409 `EmailAlreadyExistsException`), BCrypt-hashes the password, forces `Role.USER`, saves.
3. Same transaction: `user/service/EmailVerificationService.issue(user)`:
   - `invalidateAllForUser` (bulk JPQL `update ... set used = true`) kills older links.
   - `SecureTokens.generate(32)` makes a random URL-safe token; only `SecureTokens.sha256(raw)` is stored in `email_verification_tokens.token`.
   - `EmailService.send(...)` with link `FRONTEND_USER_URL/verify-email?token=<raw>`. In dev the bean is `LoggingEmailService`, which prints the email to the log.
4. User clicks the link; the frontend calls `POST /api/v1/auth/verify-email {"token": "..."}` → `EmailVerificationController` → `EmailVerificationService.verify`: hash the input, `findByToken(hash)`, reject if used or expired (`InvalidTokenException`, a `BadRequestException`, so 400), set `used = true` and `user.emailVerified = true`. Dirty checking writes both at commit.

### B. Forgot password → reset

1. `POST /api/v1/auth/forgot-password {"email"}` → `PasswordResetService.requestReset`. If the user exists, issue a 30-minute hashed token and email a link. **The response is identical either way**, so attackers can't learn which emails are registered.
2. `POST /api/v1/auth/reset-password {"token","newPassword"}` → `resetPassword`: validate the token, mark it used, set the new BCrypt hash, mark the email verified (they proved they own the inbox), and call `RefreshTokenService.revokeAllForUser`, one `UPDATE refresh_tokens SET revoked = true WHERE user_id = ?`. Every device is now signed out at its next refresh.

### C. A USER calls an admin URL

`GET /api/v1/admin/events` with a USER token: `JwtAuthenticationFilter` authenticates; the `AuthorizationFilter` sees `/api/v1/admin/** hasRole("ADMIN")` fail and throws `AccessDeniedException`; `ExceptionTranslationFilter` calls `RestAccessDeniedHandler`, which writes a JSON 403 via `ApiErrorResponseWriter`. No token at all → `RestAuthenticationEntryPoint` → JSON 401.

## 2. Key classes

| Class | Role |
|---|---|
| `common/exception/GlobalExceptionHandler` | `@RestControllerAdvice`: maps validation (400 + `fieldErrors`), malformed JSON (400), `ResourceNotFoundException` (404), `ConflictException` / `EmailAlreadyExistsException` / FK violations (409), bad credentials / refresh token (401), `AccessDeniedException` (401 if anonymous, else 403), framework status exceptions, and everything else (logged, generic 500). |
| `common/exception/ApiError` | The single error body: `timestamp, status, error, message, path, fieldErrors?`. |
| `common/security/RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`, `ApiErrorResponseWriter` | Same body for errors raised inside the filter chain, where controller advice can't reach. |
| `common/security/SecureTokens` | Random token generation + SHA-256. Shared by refresh, verification and reset tokens. |
| `common/email/EmailService` (+ `LoggingEmailService`, `EmailConfig`) | Interface for outbound email. The logging implementation is registered with `@ConditionalOnMissingBean`, so an SMTP bean would replace it with no code changes. |
| `user/service/EmailVerificationService`, `PasswordResetService` | One-time token flows. |
| `common/security/CorsConfig` | `CorsConfigurationSource` bean with an explicit allowlist from `app.cors.allowed-origins`. |
| `auth/service/GoogleAccountService` | Maps a Google identity to a local user (link or create). |
| `auth/oauth2/OAuth2LoginSuccessHandler` | Turns a successful Google login into *our* access + refresh tokens and redirects to the frontend. |
| `db/migration/V6__seed_admin_user.sql` | The one admin, BCrypt hash only. |
| `user/controller/UserController` | `GET /api/v1/users/me` from the UUID principal. |

## 3. Concepts used

- **Two error paths in Spring.** Exceptions thrown in controllers go to `@RestControllerAdvice`. Exceptions in the security filter chain (missing token, URL rule failures) never reach controllers, so they need an `AuthenticationEntryPoint` and an `AccessDeniedHandler`. Both produce `ApiError`.
- **`@PreAuthorize` failures surface in the advice.** Method security throws `AccessDeniedException` inside the dispatcher, so the advice handles it. It checks whether the caller is anonymous to choose 401 vs 403.
- **Hash one-time tokens at rest.** The emailed raw token is never stored; a DB leak can't be used to verify accounts or reset passwords. Lookup works because SHA-256 is deterministic. (Passwords use BCrypt instead, which is slow and salted, because they're low-entropy and need brute-force resistance; random 256-bit tokens don't.)
- **Single-use + invalidate-on-reissue.** `used` flags plus a bulk invalidation on every reissue mean only the latest link works.
- **No account enumeration.** `/forgot-password` and `/resend-verification` always return the same 200 body.
- **Bulk updates with `@Modifying`.** `RefreshTokenRepository.revokeAllForUser` and the token invalidations are single SQL `UPDATE`s.
- **CORS inside the security chain.** `http.cors(Customizer.withDefaults())` handles preflight `OPTIONS` before authentication, so protected endpoints don't 401 their own preflights (see `CorsTest.preflightFromAdminFrontendToProtectedRouteIsAllowed`).
- **`@ConditionalOnMissingBean` / `ObjectProvider`.** The default email bean only exists if nothing else provides one. `SecurityConfig` asks `ObjectProvider<ClientRegistrationRepository>` whether Google is configured, and only then calls `http.oauth2Login(...)`.
- **Profile-scoped config.** The Google registration lives in a `spring.config.activate.on-profile: google-oauth` document in `application.yml`, so the app starts without Google credentials.
- **Safe account linking.** Google accounts link to an existing email only when Google says `email_verified`; otherwise anyone could create a Google account with your address and take over your account. Admins are refused entirely.
- **Test infrastructure.** `support/IntegrationTest` + `TestcontainersConfiguration` give every test a real Postgres via `@ServiceConnection`, shared through Spring's test-context cache. `RecordingEmailService` (a `@Primary` bean in tests) captures emails so tests can extract tokens from links.

## 4. Try it yourself

From `backend/` with Docker running.

1. **Break the no-enumeration guarantee.** Change the body of `PasswordResetService.requestReset` to `issue(userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("No such user")));` and run:
   ```bash
   ./mvnw test -Dtest=PasswordResetTest#unknownEmailGetsTheSameResponseAndNoEmail
   ```
   It fails with 404, and that difference is exactly what an attacker would probe for. Revert.
2. **See why the entry point matters.** Comment out `.authenticationEntryPoint(authenticationEntryPoint)` in `SecurityConfig` and run `./mvnw test -Dtest=ErrorHandlingTest#missingTokenOnProtectedRouteReturnsJson401`. The status may still be 401/403, but the JSON body assertions fail. Revert.
3. **Prove reset revokes sessions.** Comment out `refreshTokenService.revokeAllForUser(user.getId());` in `PasswordResetService.resetPassword`, then run `./mvnw test -Dtest=PasswordResetTest#fullResetFlowChangesPasswordAndRevokesRefreshTokens`. The old refresh token still works, so the test fails. Revert.

## 5. Interview questions

**Q1. How do you return consistent errors from both controllers and Spring Security?**
`@RestControllerAdvice` for anything thrown in the MVC layer; a custom `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) for the filter chain. All write the same `ApiError` record, and unknown exceptions are logged and returned as a generic 500, never a stack trace.

**Q2. Why store a hash of the verification token instead of the token?**
If the DB leaks, stored tokens would be live credentials. With SHA-256 we can still look the token up (deterministic hash), but the leak is useless. BCrypt isn't needed because the token is 256 random bits, not a guessable password.

**Q3. How does your forgot-password avoid account enumeration?**
The endpoint always returns the same 200 message; the email is only sent if the account exists. Timing differences are a remaining (small) leak; rate limiting comes in Phase 6.

**Q4. What happens to existing sessions when a user resets their password?**
All refresh tokens are revoked in the same transaction, with a bulk update. Access tokens stay valid until they expire (15 minutes), which is the usual trade-off for stateless JWTs.

**Q5. Why configure CORS in Spring Security rather than with `@CrossOrigin`?**
Browser preflights carry no Authorization header. If CORS runs after authentication, preflights to protected routes get 401 and the real request never happens. `http.cors()` handles them first. The allowlist is explicit and comes from env, never `*`.

**Q6. How does Google login fit a JWT-based API?**
Spring's OAuth2 client handles the redirect dance. In the success handler I map the Google identity to a local user and issue my own access + refresh tokens, so the rest of the API only knows one token type. They're returned in the URL fragment, which browsers don't send to servers. A one-time code exchange would be the next hardening step.

**Q7. When do you auto-link a Google login to an existing account?**
Only when Google reports `email_verified: true`, the account isn't an admin, and it isn't already linked to a different Google subject. Repeat logins match on Google's stable `sub`, not on email.

**Q8. Why is the access token's `emailVerified` claim not enough for `/users/me`?**
It's frozen at issue time. After verification the claim is stale until the next refresh, so `/users/me` reads the database.
