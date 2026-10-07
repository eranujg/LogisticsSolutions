# Transport & Logistics Management Platform (TMS)

TMS for Indian transporters first; Canada, US and Australia later.

## Stack

- **Backend** (`/backend`): Spring Boot 4, Java 25, Gradle Kotlin DSL, Spring Modulith. Base package `com.aadvixon.tms`.
- **Frontend** (`/frontend`): Angular (latest), standalone components, signals, SCSS.
- **Database**: PostgreSQL 17 with PostGIS, pgvector, pg_partman, run via `docker compose`.
- **Schema**: Flyway migrations in `backend/src/main/resources/db/migration`.
- **Auth**: Keycloak (later; `--profile auth` in docker compose).

## Commands

Start infrastructure (repo root):

```bash
docker compose up -d --build
```

Backend (from `/backend`; credentials come from `../.env` via environment variables):

```bash
set -a && source ../.env && set +a && ./gradlew bootRun   # http://localhost:8080
./gradlew build -x test                                     # compile only
set -a && source ../.env && set +a && ./gradlew test        # tests (alone, see memory rules)
```

Frontend (from `/frontend`):

```bash
npm start      # ng serve on http://localhost:4200, proxies /api -> http://localhost:8080 (proxy.conf.json)
npm run build  # ng build
npm test       # ng test (vitest)
```

Health check: `GET /api/v1/system/info` returns app name, database time and tenant count.

## Rules

- **Memory**: laptop has 8 GB RAM; WSL is capped at 4 GB. Gradle stays at `-Xmx768m` (`backend/gradle.properties`), bootRun at `-Xmx512m` (`build.gradle.kts`). Never run backend tests, `bootRun` and `ng serve` at the same time.
- **Secrets**: never read or print `.env`. Database credentials are loaded only with `set -a && source ../.env && set +a`.
- **Schema**: Hibernate `ddl-auto` is `none`; Flyway owns the schema. Every schema change is a new Flyway migration.
- **Frontend HTTP**: use Angular `HttpClient` (`provideHttpClient()` in `app.config.ts`), not `fetch`.
- **Workflow**: work in small steps. Explain each step briefly, run it, verify it, then commit with a clear message. Ask before deleting files or making big structural changes.
