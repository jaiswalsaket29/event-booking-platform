# Phase 7: Production (learning notes)

Phase 7 makes the backend deployable: instants that mean the same thing everywhere, a production profile that takes every secret from the environment and refuses the published dev ones, a health endpoint for the platform, a small layered Docker image, CI, real email, and a Render Blueprint. The theme is **fail early and loudly on misconfiguration, and expose as little as possible**. Code is under `backend/src/main/java/org/saket/eventbooking/`. The deploy itself is prepared but not done (it needs a Render account).

## 1. Request walkthroughs

### A. The container starts on the platform

`docker run ... eventbooking-backend` (or Render starting the service):

1. The image's entrypoint runs `JarLauncher` with `SPRING_PROFILES_ACTIVE=prod`, so `application.yml` is loaded and then `application-prod.yml` overrides it. Placeholders like `${DB_HOST}` and `${JWT_SECRET}` have **no defaults** there, so a missing variable stops startup with "Could not resolve placeholder 'JWT_SECRET'".
2. `BackendApplication.main` pins the JVM to UTC. pgjdbc sends the JVM zone to Postgres when it connects, so this also fixes the session zone.
3. Flyway runs pending migrations (V10 converts timestamps to `timestamptz` on an existing database).
4. After all singleton beans exist, but **before Tomcat accepts requests**, two `SmartInitializingSingleton`s run:
   - `user/service/AdminPasswordBootstrap.afterSingletonsInstantiated` sets the seeded admin's password from `ADMIN_PASSWORD` if it differs (BCrypt `matches` first, so restarts are no-ops) and revokes the admin's refresh tokens when it changes;
   - `common/config/ProductionReadinessCheck.afterSingletonsInstantiated` (only with `@Profile("prod")`) calls the bootstrap itself (so bean order doesn't matter), then throws if the JWT or webhook secret is short or a published placeholder, or the admin still has `Admin@12345`.
5. Tomcat starts. Application runners run (`DemoDataSeeder` when `SEED_DEMO_DATA=true`). Only after they finish does readiness flip to ACCEPTING_TRAFFIC.

### B. The platform's health check

`GET /actuator/health/readiness`:

1. `SecurityConfig` permits `GET /actuator/health/**` anonymously; any other `/actuator/**` path requires ADMIN.
2. Actuator exposes only `health` (`management.endpoints.web.exposure.include: health`). The readiness group includes `readinessState`, `db` and `redis`, so it runs `SELECT 1`-style checks against Postgres and a Redis `PING`.
3. The response is just `{"status":"UP"}`: `show-details` and `show-components` are `never`, so no hostnames or versions leak.
4. While the seeder runs, or if Postgres or Redis is down, readiness is not UP and the platform stops sending traffic. Liveness (`livenessState` only) stays UP, so the platform doesn't restart a healthy JVM because the database blinked.

### C. A pull request

`.github/workflows/ci.yml`: `backend` runs `./mvnw -B verify` on Temurin 25 (Testcontainers uses the runner's Docker), `backend-image` runs `docker build backend`, and `frontends` runs `npm ci/lint/build` for each app whose `package.json` exists.

## 2. Key classes and files

| File | Role | Without it |
|---|---|---|
| `db/migration/V10__timestamps_with_time_zone.sql` | `timestamp` → `timestamptz`, existing values read as Asia/Kolkata | Stored times depend on the writer's session zone |
| `BackendApplication` | Pins the JVM to UTC | Postgres rejected this Windows machine's `Asia/Calcutta` zone id |
| `application-prod.yml` | Production config from env vars, no secret defaults | Prod could start with dev values |
| `common/config/ProductionReadinessCheck` | Refuses known-weak secrets and the dev admin password | Anyone could forge JWTs or webhooks with the published secrets |
| `user/service/AdminPasswordBootstrap` | Admin password from `ADMIN_PASSWORD` | Changing the seeded password would need manual SQL |
| `common/email/SmtpEmailService` + `EmailConfig` | Real email when `SPRING_MAIL_HOST` is set | A deployed site couldn't verify users, so nobody could book |
| `backend/Dockerfile`, `.dockerignore` | Two-stage, layered, non-root image | Fat image with a JDK; whole jar re-shipped on every change |
| `.github/workflows/ci.yml`, `.gitattributes` | CI; LF endings and exec bit for `mvnw` | Red builds merge; `mvnw` fails on Linux |
| `render.yaml`, `docs/deployment.md` | Infrastructure and the guide | Deploy is a manual click-through |

## 3. Concepts used

- **`timestamp` vs `timestamptz`**: `timestamp` stores a wall-clock reading with no zone, so its meaning depends on who wrote it. `timestamptz` stores an absolute instant (internally UTC) and converts on output. `TimestampColumnsTest` reads one row under three session zones and gets the same `Instant`.
- **Profile-specific config**: `application-{profile}.yml` overrides the base file. `${VAR}` without `:default` makes a variable mandatory.
- **Startup hooks and their timing**: `@PostConstruct` (one bean, possibly before its dependencies are fully ready) → `SmartInitializingSingleton` (all singletons ready, before the web server starts) → `ApplicationRunner` (after the server starts, before readiness is UP). The guard uses the second so a bad deploy never serves a request.
- **Liveness vs readiness**: liveness means "restart me if this fails", readiness means "don't route traffic to me". Dependencies belong in readiness only.
- **Least exposure**: only `health` is exposed; the security rule for `/actuator/**` is a second fence in case someone widens exposure later.
- **Multi-stage builds and layering**: the build stage has Maven and the JDK; the runtime has only a JRE. `-Djarmode=tools extract --layers` splits dependencies (rarely change) from application classes (change every commit), so Docker reuses layers.
- **Container-aware JVM**: `-XX:MaxRAMPercentage=75` sizes the heap from the container limit; `-XX:+ExitOnOutOfMemoryError` lets the platform restart a dead JVM.
- **Trusted proxies**: `server.forward-headers-strategy: native` enables Tomcat's `RemoteIpValve`, which only trusts `X-Forwarded-For` from internal proxy addresses. That matters for per-IP rate limits.
- **Line endings and file modes in git**: `core.autocrlf` gives Windows CRLF checkouts; `.gitattributes` forces LF for shell scripts. `git update-index --chmod=+x` records the exec bit that Windows can't.

## 4. Try it yourself

1. **Watch the guard refuse a dev secret.**
   ```
   ./mvnw test -Dtest=ProdProfileStartupTest
   ```
   Then, in `ProductionReadinessCheck.afterSingletonsInstantiated`, comment out the `throw new IllegalStateException(...)` line and rerun: `refusesToStartWithTheDevWebhookSecret` fails because the app now starts happily with a published secret. Note that the 33-character dev secret would pass a length check alone; it's caught by the placeholder rules in `weak()` and the explicit `DEV_WEBHOOK_SECRET` comparison.
2. **See why timestamptz matters.** In psql against the dev database:
   ```
   docker exec -it backend-postgres-1 psql -U eventbooking -d eventbooking
   set time zone 'Asia/Kolkata'; select created_at from users where role = 'ADMIN';
   set time zone 'UTC';          select created_at from users where role = 'ADMIN';
   ```
   The display changes but it's the same instant (`... +05:30` vs `... +00`). With the old `timestamp` column, both queries showed the same digits, and nothing said which zone they were in.
3. **Run the production image** with the `docker run` command in `docs/deployment.md`, then `curl localhost:8098/actuator/health/readiness` while the seeder runs (`OUT_OF_SERVICE`) and again a few seconds later (`UP`). Try `curl localhost:8098/actuator/env` (401) and `curl localhost:8098/v3/api-docs` (404).

## 5. Interview questions

1. **How do you keep secrets out of a public repo but still have a working dev setup?** Dev defaults live in config and `.env.example` and are published on purpose. Production uses a separate profile where secrets have no defaults, and a startup check rejects the published values, so "forgot to set it" fails loudly instead of running with a known key.
2. **Why check configuration before the web server starts?** A platform considers an instance alive once the port answers. Failing in `SmartInitializingSingleton` means a misconfigured release never serves a request, and the deploy shows a clear error. An `ApplicationRunner` would run after the port opens.
3. **Liveness vs readiness, and where does the database go?** Readiness. If the database is down, a restart doesn't help and a restart loop makes recovery slower; taking the instance out of rotation is the right response. Liveness should only fail when the process itself is broken.
4. **Why convert to timestamptz, and how did you migrate existing data?** Zone-less timestamps meant the stored value depended on the JVM/session zone, which differed between the app (Kolkata), tests (UTC) and `now()` defaults. The migration reads each old value `AT TIME ZONE 'Asia/Kolkata'`, because that's how it was written, and was checked on the dev database (21:42 IST became 16:12Z, the API output unchanged).
5. **Walk me through your Dockerfile.** Build stage with Maven caches dependencies via `dependency:go-offline`, packages, and extracts layers. Runtime stage is a JRE image, copies layers from least to most volatile, runs as a non-root user, and sets container-aware memory flags. Tests run in CI rather than in the image build because they need Docker themselves.
6. **What does CI check, and what doesn't it?** The full Testcontainers suite, that the image builds, and frontend lint/build once they exist. It doesn't deploy or push images (the platform builds from the Dockerfile), and it hadn't run yet at the time of writing because nothing had been pushed.
7. **Why did you add SMTP in a phase about deployment?** Booking requires a verified email, and with only the logging email service a deployed site would write verification links to server logs. SMTP sits behind the existing interface, turns on only when configured, and logs failures instead of rolling back a signup.
