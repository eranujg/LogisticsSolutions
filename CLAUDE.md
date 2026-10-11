# Transport & Logistics Management Platform (TMS)

TMS for Indian transporters first; Canada, US and Australia later.

## Stack

- **Backend** (`/backend`): Spring Boot 4, Java 25, Gradle Kotlin DSL, Spring Modulith. Base package `com.aadvixon.tms`.
- **Frontend** (`/frontend`): Angular (latest), standalone components, signals, SCSS.
- **Database**: PostgreSQL 17 with PostGIS, pgvector, pg_partman, run via `docker compose`.
- **Schema**: Flyway migrations in `backend/src/main/resources/db/migration`.
- **Auth**: Keycloak locally (`docker compose --profile auth up -d`, realm `tms` imported from `docker/keycloak/tms-realm.json`); Cognito in AWS.

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

## Frontend structure

- UI library: **Angular Material** (MIT). Do not add PrimeNG/PrimeIcons: since v22 they need a paid
  licence key. Icons: `material-symbols` (Apache-2.0). Font: self-hosted `@fontsource/instrument-sans`.
- Sign-in: `core/auth` uses `oidc-client-ts` (authorization code + PKCE) against Keycloak
  (`core/config.ts`); tokens in session storage. `authInterceptor` adds the bearer token to `/api/v1`
  calls and redirects to sign-in on 401.
- `core/session/SessionService` loads `GET /api/v1/me`; use `session.can('party.create')` to show or
  hide actions and `permissionGuard('party.view')` on routes. The backend still enforces everything.
- `core/api/ApiService` + `models.ts` mirror the backend records. Screens live in `features/<area>`,
  lazy-loaded from `app.routes.ts`, menu in `layout/shell.ts` (`NAV`, filtered by permission).
- Edit forms open as MatDialog templates; deletes go through `DeleteDialog.ask(...)` (asks for a reason).
  Messages via `NotifyService`. Shared page styles (`page-head`, `panel`, `form-grid`, `tag`) are in
  `src/styles.scss`.

## Backend structure

Top-level packages under `com.aadvixon.tms` are modules: `platform` (tenancy, audit, recycle bin, numbering,
tenant provisioning, errors), `company` (country packs, company, tax registrations), `location`, `geo`
(cities, city services), `iam` (users, roles, permissions), `party` (consignors/consignees/bill-to), `rate` (charge heads,
rate cards, `RateEngine` for rate lookup: client card, then standard rates; most specific line wins).
Data access uses `JdbcClient` with explicit SQL; row mappers use `platform.db.Rows`.

## Multi-tenancy (read before touching data access)

- Every tenant table has `tenant_id` and is secured in its migration with
  `SELECT tms_secure_tenant_table('<table>');` (RLS policy + audit trigger + `updated_at` trigger).
- `TenantAwareDataSource` prepares each pooled connection: `SET ROLE tms_app` plus `app.tenant_id` /
  `app.user_id` from `TenantContext`. RLS then limits every query to the current tenant.
- Set the tenant **before** starting a transaction (connections are prepared when taken from the pool).
- `TenantContext.runAsSystem` bypasses RLS (pool user). Only for platform work such as tenant provisioning.
- Dev only: tenant/user come from `X-Tenant-Id` / `X-User-Id` headers (`tms.tenancy.header-enabled`), and
  `POST /api/v1/platform/tenants` is open (`tms.platform.admin-api-enabled`). Both must be false outside
  local development; real login (Keycloak / Cognito) replaces them.
- Masters are soft-deleted (`deleted_at`, `deleted_by`, `delete_reason`) and appear in the recycle bin
  (`platform.recyclebin.RecycleBin` lists allowed tables). Issued documents are cancelled, never deleted.
- `audit_log` is append-only and written only by triggers. Document numbers come from
  `NumberSeriesService.next(...)` inside the transaction that saves the document (gapless).

## Login and permissions

- Spring Security OAuth2 resource server validates bearer JWTs (`tms.security.jwt.issuer-uri`,
  default `http://localhost:8180/realms/tms`). An unreachable identity provider gives 401, not 500.
- Tenant comes from Keycloak groups `/tenants/<tenant-code>` (`groups` claim); `X-Tenant-Code` picks
  one when a login belongs to several. The `app_user` is found by `external_subject` (token `sub`) or,
  on first login, linked by email. `GET /api/v1/me` returns the user and permission codes.
- `platform.security.PermissionInterceptor` checks `<module>.<action>` for every `/api/v1/**` call:
  path segment -> module (`MODULES` map), GET=view, POST=create, PUT/PATCH=edit, DELETE=delete,
  `.../restore`=edit. **Unknown paths are denied**: register every new resource in `MODULES`.
- Dev header mode (no token) stays unrestricted. Dev realm users: `owner@demo.local` /
  `DemoOwner-2026`, `clerk@demo.local` / `DemoClerk-2026` (group `/tenants/demo`; they still need
  an `app_user` with that email in tenant `demo`). Development passwords only.

## CI

GitHub Actions (`.github/workflows/ci.yml`): gitleaks, backend `./gradlew build` (Testcontainers
PostgreSQL 17), frontend build + tests on Node 24. Backend failures are summarised in one annotation.

## Rules

- **Memory**: laptop has 8 GB RAM; WSL is capped at 4 GB. Gradle stays at `-Xmx768m` (`backend/gradle.properties`), bootRun at `-Xmx512m` (`build.gradle.kts`). Never run backend tests, `bootRun` and `ng serve` at the same time.
- **Secrets**: never read or print `.env`. Database credentials are loaded only with `set -a && source ../.env && set +a`.
- **Schema**: Hibernate `ddl-auto` is `none`; Flyway owns the schema. Every schema change is a new Flyway migration.
- **Frontend HTTP**: use Angular `HttpClient` (`provideHttpClient()` in `app.config.ts`), not `fetch`.
- **Workflow**: work in small steps. Explain each step briefly, run it, verify it, then commit with a clear message. Ask before deleting files or making big structural changes.
