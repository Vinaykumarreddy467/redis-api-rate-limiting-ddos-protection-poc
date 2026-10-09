# Codebase Memory: Redis API Rate Limiting and DDoS Protection POC

This file is a working map for future codebase tasks. Treat source/configuration as authoritative when README counts or examples disagree; several documentation files record older snapshots.

## Overview and purpose

This is a proof of concept for API rate limiting enforced consistently across multiple Spring Boot instances using Redis as shared state. It includes a live Angular operator console, dynamic policy and exemption administration, health/metrics, demo routes, and PowerShell scripts that exercise limits and cross-instance behavior. It demonstrates six Redis-backed algorithms and per-policy Redis failure modes. It is a demonstration/control-plane project, not a production DDoS mitigation edge or business API.

Request path: Spring MVC filter resolves a route policy and client identity, checks route exemptions, evaluates all applicable enabled policies atomically against Redis, then either continues or returns 429 with rate-limit headers. Policies compose with AND: all applicable policies must allow; a denial does not charge other policy counters. User identity falls back to IP if unauthenticated. Forwarded IP headers are only trusted from configured proxy CIDRs.

**Policy groups (Phase 4)** compose multiple scope rules per endpoint. A group has an `enabled` flag, endpoints (method, path, displayName, repeatable, exempt), and each endpoint carries one or more `ScopeRule` (scope, algorithm, parameters). Groups are validated against global rules and other groups via `PolicyGroupValidator`; projections are materialized as individual `PolicyDocument` records in Redis so the enforcement path is unchanged. The admin UI provides a group editor; the request tester lets you select a group endpoint directly.

## Tech stack

- Backend: Java 21, Spring Boot 3.5.16, Maven; Spring MVC/Web, Spring Data Redis with Lettuce and commons-pool2, Spring Security, Actuator/Micrometer, Jakarta validation.
- Redis: Redis 7 Alpine via Docker Compose; AOF enabled with a named data volume. Lua scripts provide atomic multi-key algorithm operations. The implementation targets standalone Redis; cluster behavior is unsupported.
- Frontend: Angular 22 standalone components, TypeScript 6, RxJS 7, Angular Router/Forms, SCSS; Angular CLI and Vitest/jsdom. No UI component framework.
- Tests: Spring Boot test/JUnit and Testcontainers Redis on backend; Angular CLI/Vitest on frontend.
- Local orchestration/evidence: PowerShell scripts and Windows `.bat` launchers; Node/npm for frontend.

## Project structure

- `redis-rate-limit-poc/src/main/java/com/example/ratelimit/`
  - `ratelimit/`: `RateLimitFilter`, identity and policy resolution, store interface/Redis implementation, decisions and metrics.
  - `policy/`: policy/exemption documents, enums, validation/matching/enforcement, Redis-backed managed policy store, seeding and audit. **New: `PolicyGroup`, `EndpointRule`, `ScopeRule`, `PolicyGroupValidator`, `ProjectionService`, `EndpointExemptionService`, `GlobalScopeRules`.**
  - `admin/`: authenticated policy, capability, exemption and audit REST API plus DTOs. **New: group and global-rules endpoints.**
  - `config/`: typed `rate-limit` and admin properties plus filter/configuration setup.
  - `web/`: demo API, dynamic demo-route catalog, metadata controller, security config and access logging.
- `redis-rate-limit-poc/src/main/resources/application.yml`: defaults, Redis and server config, seeded policies, actuator exposure.
- `redis-rate-limit-poc/src/test/java/`: backend configuration, store, failure-mode, identity, boundary, concurrency, HTTP, policy and admin tests; Testcontainers support. **New: `PolicyGroupValidatorTest`, `PolicyMatcherGroupTest`, `EndpointExemptionServiceTest`.**
- `redis-rate-limit-poc/scripts/`: `verify-all.ps1`, load/two-instance/concurrency/admin cross-instance demos, helper timing and launch scripts.
- `redis-rate-limit-poc/docs/api-rate-limiting-poc.md`: detailed algorithm/design notes (some counts/claims may be from an older snapshot).
- `frontend/src/app/core/`: API clients, typed API/admin models, status/store services, route/demo catalog and demo runner. **New: `ResponseEntry` model, updated `DemoSummary` with `responses[]`.**
- `frontend/src/app/features/`: overview/dashboard, request demo, admin login, policy editor/list and audit views. **New: `policy-group-editor` component, request demo per-response collapsible entries.**
- `frontend/proxy.conf.json`: forwards `/api` and `/actuator` to backend `localhost:8080` for dev.
- Root `docs/`: screenshots and generated report; root `README.md` is the main user-oriented guide. `start.bat`/`stop.bat` wrap root PowerShell lifecycle scripts.

## Data, policies and APIs

### Policy model

Managed `PolicyDocument` records include id/name, method/path, algorithm, scope, algorithm-specific parameters, enabled flag, `onRedisError`, optimistic version, created/updated timestamps, and updater principal. Requests/responses are DTOs in `admin/AdminDtos.java`; versioned updates use compare-and-set and return ETags. Mutations are audited by principal name. Policy documents and audit live in Redis namespace `ratelimit:policy:v1:*`; exemptions have their own `ratelimit:exemption:v1` namespace. Runtime counter keys use `rate-limit:v1` and algorithm-specific subkeys.

Algorithms: `FIXED_WINDOW`, exact `SLIDING_WINDOW`, approximate `SLIDING_WINDOW_COUNTER`, `TOKEN_BUCKET`, leaky-bucket policing (`LEAKY_BUCKET`), and distributed lease-based `CONCURRENCY_LIMIT`. Scopes: `ENDPOINT`, `IP`, `USER`, `GLOBAL`, and `APPLICATION`. Applicable enabled policies all need to pass. Default seeded routes from `application.yml`: `GET /api/products` 100/min/IP (fail open), `POST /api/login` 10/min/IP (fail closed), `POST /api/orders` 30/min/USER (inherits global fail-open). Dynamic policies in Redis take precedence after seeding. `GLOBAL` means a shared quota across all routes and identities; `APPLICATION` is shared across API routes regardless of identity.

**Policy groups** (`PolicyGroup`): id, name, enabled, endpoints[], onRedisError, version, timestamps, updater. Each `EndpointRule`: id, method, path, displayName, repeatable, exempt, scopeRules[]. Each `ScopeRule`: scope, algorithm, window, limit, capacity, refillInterval, cost, drainRate, queueCapacity, maxConcurrent, leaseDuration, onRedisError. Groups are projected to individual `PolicyDocument` records via `ProjectionService.projectionId(groupId, endpointId, scope)`. Validation via `PolicyGroupValidator` checks for conflicts with global rules and other groups.

### HTTP endpoints

- Demo routes: `GET /api/products`, `POST /api/login`, `POST /api/orders`, `GET /api/work` (see `DemoController.java` for authentication/behavior and `@DemoCallable` flags).
- Metadata: `GET /api/poc/policies`.
- Admin base `/api/admin/rate-limit` (requires `ROLE_ADMIN`):
  - `GET /policies`, `GET /policies/{id}`, `POST /policies`, `PUT /policies/{id}`, `PATCH /policies/{id}/enabled`, `DELETE /policies/{id}`, `POST /policies/reset`.
  - `GET /capabilities`, `GET /audit?limit=50`, `GET /demo-routes`.
  - `GET /exemptions`, `POST /exemptions`, `DELETE /exemptions/{id}`.
  - **Groups:** `GET /groups`, `GET /groups/{id}`, `POST /groups`, `PUT /groups/{id}`, `DELETE /groups/{id}`, `DELETE /groups/{groupId}/endpoints/{endpointId}`, `POST /groups/{id}/repair`.
  - **Global rules:** `GET /global-rules`, `PUT /global-rules`.
- Actuator: `/actuator/health`, `/actuator/info`, `/actuator/metrics` (only these are exposed in config). Rate-limit observations use Micrometer `ratelimit.requests` with outcome tags.
- Rejections return HTTP 429 and rate limit response headers; filter/store implementation is authoritative for exact header set.
- Security: `/api/admin/**` is protected with HTTP Basic ROLE_ADMIN; demo `alice`/`bob` are users, not admins. Admin credentials have no backend default.

## Development setup

Prerequisites: Java 21, Maven, Node/npm compatible with Angular CLI 22, and Docker Desktop/Engine for Redis and Testcontainers.

From repository root on Windows, recommended full local launch:

```powershell
.\start.bat
.\stop.bat
```

The start wrapper sets local demo credentials `pocadmin` / `admin123`, starts/reuses the Compose Redis container, builds backend, starts backend and Angular console, checks readiness, and opens the browser. Treat those credentials as local demo-only. Backend-only manual setup:

```powershell
cd redis-rate-limit-poc
docker compose up -d
mvn -B spring-boot:run
```

Frontend in another shell:

```powershell
cd frontend
npm ci
npm start
```

Build/test:

```powershell
cd redis-rate-limit-poc
mvn -B clean verify
cd ..\frontend
npm test
npm run build
```

End-to-end demos are under `redis-rate-limit-poc/scripts/`; run `verify-all.ps1` there. It accepts `-SkipBuild`, `-SkipUnitTests`, `-KeepRunning`; two-instance proof is `two-instance-demo.ps1`. Root README reports historical observed counts; consult live test output for current totals.

### Environment/configuration

- `SERVER_PORT` (default `8080`)
- `REDIS_HOST` (default `localhost`), `REDIS_PORT` (default `6379`)
- `RATELIMIT_ADMIN_USER`, `RATELIMIT_ADMIN_PASSWORD` (required to enable admin login; no default in backend config)
- YAML `rate-limit.*` config controls `enabled`, `key-prefix`, `trusted-proxies`, Redis failure behavior, TTL grace, excluded paths/methods, and bootstrap policies. Admin binding is represented by `ratelimit.admin.*` (`AdminProperties`).
- `frontend/src/app/core/api-config.ts` defaults to relative/same-origin API paths; dev proxy provides backend connection. No frontend environment variable is required by the checked-in config.

## Notes for future work

- Start with source under `src/main` and current tests; README and design/report docs contain historical test counts and may not match the current repository state.
- Avoid putting credentials into tracked config. Root `start.bat` deliberately supplies demo-only credentials for local launch.
- Redis reset with `docker compose down -v` deletes persisted managed policies/data; plain `down` preserves the named volume.
- Tests require Docker/Redis through Testcontainers; if Docker is unavailable they may fail before exercising application behavior.
- **Verified test counts (2026-10-08):** Backend 153 tests pass, Frontend 84 tests pass, Frontend build succeeds.
