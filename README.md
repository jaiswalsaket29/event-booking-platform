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

The admin account is seeded by Flyway migration `V6__seed_admin_user.sql`. These credentials are for local development only. With the `prod` profile the app sets the admin password from `ADMIN_PASSWORD` and refuses to start while the dev password (or the dev JWT/webhook secrets) are still in use.

## Tests and CI

```bash
cd backend
./mvnw verify               # needs Docker running (Testcontainers starts Postgres and Redis)
```

GitHub Actions (`.github/workflows/ci.yml`) runs the same `./mvnw verify` on every push to `main` and every pull request, builds the Docker image, and lints/builds the frontends once they exist.

## Deployment

The backend runs from `backend/Dockerfile` with the `prod` profile; every secret comes from environment variables, and it refuses to start with the dev secrets above. API docs are off in production; locally they're at http://localhost:8080/swagger-ui.html. `render.yaml` is a Render Blueprint for the API, PostgreSQL and Redis. See [`docs/deployment.md`](docs/deployment.md) for the variables, the Render steps and how to run the production image locally.

