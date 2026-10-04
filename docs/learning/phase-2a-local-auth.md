# Phase 2a: Local auth (learning notes)

Backfilled. Phase 2a added email/password signup and login with BCrypt and a stateless JWT security chain.

## 1. Request walkthroughs

### A. Login

`POST /api/v1/auth/login {"email","password"}`

1. `SecurityConfig` permits `/api/v1/auth/**`.
2. `auth/controller/AuthController.login` (`@Valid LoginRequest`) → `AuthService.login`.
3. `UserService.findByEmail(normalized email)` → `UserRepository.findByEmail` → `SELECT ... FROM users WHERE email = ?`. Not found → `InvalidCredentialsException` (401).
4. If `passwordHash` is null (Google-only account) or `passwordEncoder.matches(raw, hash)` is false → the same exception. Unknown email and wrong password give the same answer.
5. `JwtTokenProvider.generateAccessToken(user)`: HS256-signed JWT with `sub = userId`, claims `email`, `role`, `emailVerified`, 15-minute expiry. A refresh token is issued too (Phase 2b).
6. Response `AuthResponse(accessToken, refreshToken, user)`.

### B. Authenticated request

`GET /api/v1/users/me` with `Authorization: Bearer <jwt>`

1. `common/security/JwtAuthenticationFilter` (a `OncePerRequestFilter` registered before `UsernamePasswordAuthenticationFilter`) reads the header and calls `JwtTokenProvider.validateAndParse`, which checks signature and expiry.
2. On success it puts a `UsernamePasswordAuthenticationToken(principal = UUID userId, authorities = [ROLE_<role>])` into the `SecurityContextHolder`. On any `JwtException` it clears the context and continues, so the request is treated as anonymous.
3. `AuthorizationFilter` checks the URL rules; the controller gets the UUID via `@AuthenticationPrincipal UUID userId`.

## 2. Key classes

| Class | Role |
|---|---|
| `common/security/SecurityConfig` | Stateless chain: CSRF off, `SessionCreationPolicy.STATELESS`, URL rules, the JWT filter, `@EnableMethodSecurity` for `@PreAuthorize`, and the `BCryptPasswordEncoder` bean |
| `common/security/JwtTokenProvider` | Issues and parses access tokens (jjwt 0.12, `Keys.hmacShaKeyFor`, so the secret must be at least 32 bytes) |
| `common/security/JwtAuthenticationFilter` | Bearer token → `SecurityContext` |
| `auth/service/AuthService` | Signup (forces `Role.USER`, hashes the password) and login |
| `user/dto/UserResponse` | What clients see; never includes `passwordHash` |

## 3. Concepts used

- **Stateless authentication.** No server session; every request carries a signed token. This scales horizontally and needs no session store, but a token can't be revoked before it expires, which is why access tokens are short-lived and refresh tokens exist (Phase 2b).
- **BCrypt.** Slow and salted on purpose, so leaked hashes are expensive to brute-force. `matches` re-hashes with the stored salt.
- **Generic credential errors.** "Invalid credentials" whether the email or the password is wrong, so login can't be used to probe for accounts.
- **Server-side role assignment.** Signup ignores any role in the payload (`Role.USER` is hardcoded), so nobody can sign up as ADMIN.
- **Principal = UUID.** Simpler than loading a `UserDetails` on every request; services load the user only when they need it.
- **CSRF disabled.** Safe here because auth is a header the browser doesn't attach automatically, unlike cookies.
- **Filter ordering.** The JWT filter must run before Spring's authentication filters so the context is populated when authorization is checked.

## 4. Try it yourself

1. Get a token: `curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"admin@eventbooking.dev","password":"Admin@12345"}'`. Paste the `accessToken` into jwt.io and look at the claims (it's signed, not encrypted: never put secrets in a JWT).
2. Change one character of the token's signature and call `curl -H "Authorization: Bearer <tampered>" localhost:8080/api/v1/users/me`: 401, because the signature check fails and the request is anonymous.
3. In `JwtAuthenticationFilter`, change the `catch` block to rethrow (`throw e;`) instead of clearing the context, then run `./mvnw test -Dtest=ErrorHandlingTest#garbageTokenIsTreatedAsUnauthenticated`. A bad token now blows up the request instead of being treated as anonymous. Revert.

## 5. Interview questions

**Q1. Why JWT for access tokens?**
Stateless verification: any instance can check the signature without a DB lookup. The cost is no early revocation, mitigated by a 15-minute lifetime plus revocable refresh tokens.

**Q2. What's in your token and what's not?**
User ID, email, role, emailVerified, issued-at and expiry. No secrets: JWTs are only base64-encoded and signed.

**Q3. Why is the principal a UUID instead of a UserDetails?**
Every request would otherwise need a DB hit. The token already proves identity and role; services fetch the user when they actually need user data.

**Q4. Why disable CSRF?**
CSRF exploits credentials the browser sends automatically (cookies). Bearer tokens in headers aren't sent automatically, so CSRF doesn't apply. If tokens moved to cookies, CSRF protection would have to come back.

**Q5. How do you stop users from making themselves admin?**
Signup sets `Role.USER` server-side; DTOs don't even have a role field. Admins come from a migration.
