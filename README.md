# Event Booking Platform

An event ticket booking platform (concerts, comedy, theatre, movies): a Spring Boot backend plus two React frontends (user site and admin dashboard). Work in progress; see [`docs/roadmap.md`](docs/roadmap.md).

## Running locally

```bash
cd backend
docker compose up -d        # Postgres 16 on :5433, Redis 7 on :6379
cp .env.example .env        # then set JWT_SECRET (at least 32 bytes)
./mvnw spring-boot:run      # API on http://localhost:8080
```

Use `.\mvnw.cmd` instead of `./mvnw` in Windows PowerShell.

## Dev credentials (local only)

| Role  | Email                    | Password      |
|-------|--------------------------|---------------|
| Admin | `admin@eventbooking.dev` | `Admin@12345` |

The admin account is seeded by Flyway migration `V6__seed_admin_user.sql`. These credentials are for local development only; change the password before any real deployment.
