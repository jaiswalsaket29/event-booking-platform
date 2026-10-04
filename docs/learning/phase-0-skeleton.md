# Phase 0: Skeleton (learning notes)

Backfilled. Phase 0 set up the monorepo, the Spring Boot 4.1 / Java 25 backend, and local infrastructure.

## 1. Request walkthrough: what happens on `./mvnw spring-boot:run`

1. `BackendApplication.main` sets the JVM default time zone to `Asia/Kolkata`, then calls `SpringApplication.run`.
2. Spring Boot reads `src/main/resources/application.yml`. `springboot4-dotenv` adds `backend/.env` as a property source, so `${JWT_SECRET}` resolves. (Without `.env` or an env var, startup fails because the secret has no default.)
3. The datasource connects to the docker-compose Postgres on **host port 5433** (`jdbc:postgresql://localhost:5433/eventbooking`).
4. Flyway runs pending migrations from `classpath:db/migration`, then Hibernate starts with `ddl-auto: validate` and checks every `@Entity` against the tables.
5. Embedded Tomcat listens on 8080.

## 2. Key files

| File | Why it exists |
|---|---|
| `backend/docker-compose.yml` | Postgres 16 (5433→5432) and Redis 7 (6379). Port 5433 avoids clashing with a natively installed Postgres on the dev machine (commit `55f0f3e`). |
| `backend/src/main/resources/application.yml` | Datasource, JPA (`validate`, SQL logging), Flyway, JWT and `app.*` settings. |
| `backend/pom.xml` | Boot 4 starters (`spring-boot-starter-webmvc`, explicit `spring-boot-starter-flyway`), Lombok wired into the compiler plugin, Testcontainers BOM, Surefire with `-Duser.timezone=UTC`. |
| `BackendApplication` | Entry point; pins the JVM time zone. |
| `.gitignore` | Keeps `.env`, build output and IDE files out of git. |

## 3. Concepts used

- **Spring Boot 4 starter names.** Boot 4 split the web starter: it's `spring-boot-starter-webmvc`, not `-web`. Flyway needs its own starter plus `flyway-database-postgresql`. Many tutorials still show Boot 3 names.
- **Why pin the time zone?** The Windows JVM reported `Asia/Calcutta`, which Postgres 16 rejected as a session `TimeZone` (commit `7de5d1f`). Setting `Asia/Kolkata` fixed startup. Tests run with `-Duser.timezone=UTC` (Surefire `argLine`) so time-based assertions are deterministic.
- **Externalised secrets.** Secrets come from env vars or `.env` (gitignored), with `backend/.env.example` documenting every variable with placeholders.
- **Infrastructure as compose.** One `docker compose up -d` gives every developer the same Postgres/Redis versions.

## 4. Try it yourself

1. Stop the containers (`docker compose stop`) and run `./mvnw spring-boot:run`: startup fails at the datasource. That's why the setup steps bring compose up first. Start them again with `docker compose up -d`.
2. Rename `.env` temporarily and start the app: it fails resolving `${JWT_SECRET}`. Restore it.
3. Run `docker compose ps` and `docker compose exec postgres psql -U eventbooking -c "\dt"` to see the tables Flyway created.

## 5. Interview questions

**Q1. Why Docker Compose for local dev?**
Same versions for everyone, a disposable database, and no local installs. Tests use Testcontainers instead, so they don't depend on compose at all.

**Q2. Why map Postgres to 5433?**
A native Postgres was already on 5432. Changing the host port avoids the conflict without touching the container.

**Q3. How do you keep secrets out of git?**
`.env` is gitignored and loaded by spring-dotenv locally. In production the same variables come from the platform's environment, and `.env.example` documents them with placeholders.

**Q4. Why is the JVM time zone set in `main`?**
The JDBC driver sends the JVM zone to Postgres, and the legacy alias `Asia/Calcutta` was rejected. Timestamps are stored as `Instant`s, so the zone doesn't change stored values; it only had to be one Postgres accepts.
