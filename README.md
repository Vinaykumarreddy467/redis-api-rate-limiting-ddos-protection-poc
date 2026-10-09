# Redis API Rate Limiting and DDoS Protection POC

A working proof of concept for API rate limiting with Redis as the shared counter store, plus an
Angular console that shows the limit being hit in a browser.

- **Backend** — Spring Boot 3.5.16 on Java 21, six Redis-backed rate algorithms (fixed window,
  exact sliding window, sliding-window counter, token bucket, leaky-bucket policing, distributed
  concurrency leases) enforced atomically, dynamic admin-managed policies, Actuator counters exposed
  for evidence.
- **Frontend** — Angular 22 standalone components, no UI framework, talks to the backend through a
  dev proxy.
- **Proof** — the same counter is enforced by **two separate JVMs**, so the limit is genuinely
  shared state and not per-process bookkeeping.

Everything below was run and observed on this machine. Numbers are measured, not aspirational.

---

## 1. What it demonstrates

| # | Claim | Where it is proven |
|---|-------|-------------------|
| 1 | A limit is enforced at exactly the configured number | `05-request-demo-429-results.png`, first 429 at request **#101** for a limit of 100 |
| 2 | Rejections are well-formed 429s, not connection drops | `06-response-inspector-headers.png` — `Retry-After: 59 s`, `X-RateLimit-Limit: 100`, `X-RateLimit-Remaining: 0` |
| 3 | The limit is **global**, not per instance | `08-two-instance-demo-results.png` — two JVMs on 18081/18082, **60** requests split across both, one shared key with `pttl 17760` |
| 4 | Counting survives a Redis restart | `TTL` and counter expiry in `verify-all.ps1` |
| 5 | The system degrades predictably when Redis dies | Per-policy fail-open / fail-closed below |
| 6 | The whole thing is testable headlessly | **153 backend tests + 84 frontend tests**, green |
| 7 | Policy groups compose multiple scope rules per endpoint | Admin UI group editor + `PolicyGroupValidator` tests |
| 8 | Request tester shows every response with pretty JSON | Request demo component — collapsible per-request entries |

---

## 2. Architecture

```
                      ┌─────────────────────────────────────────────┐
                      │  Angular console  (ng serve :4200)          │
                      │  overview · policies · request demo ·        │
                      │  response inspector · two-instance view      │
                      └───────────────────┬─────────────────────────┘
                                          │ HTTP via proxy.conf.json
                      ┌───────────────────▼─────────────────────────┐
   client IP ────────►│  Spring Boot API  (:8080)                   │
   or user identity   │                                             │
                      │  RateLimitInterceptor  ──►  policy lookup   │
                      │        │                         │           │
                      │        │  INCR key + EXPIRE       │           │
                      │        ▼                         ▼           │
                      │  429 + standard headers    Actuator counters │
                      └───────────────────┬─────────────────────────┘
                                          │ Lettuce / commons-pool2
                      ┌───────────────────▼─────────────────────────┐
                      │  Redis 7 (docker, :6379)                    │
                      │  ratelimit:{policy}:{identity}              │
                      │  INCR + TTL  → one shared counter           │
                      └─────────────────────────────────────────────┘
```

### Request flow

1. Client calls a protected route.
2. `RateLimitInterceptor` resolves the **identity** — client IP by default, authenticated user
   when the request carries a session.
3. An **exemption** for that route + method is checked first. If one matches, the request proceeds
   untouched — no quota is charged, bypassing even GLOBAL/APPLICATION policies. Otherwise the
   **policy** for that route + method is looked up. Unknown routes are not limited.
4. `INCR ratelimit:{policy}:{identity}` in Redis. The first increment of a window also sets the TTL.
5. `count > limit` → **429 Too Many Requests** with `Retry-After`, `X-RateLimit-Limit`,
   `X-RateLimit-Remaining`, `X-RateLimit-Policy`. Otherwise the request proceeds untouched.
6. The outcome is counted in Actuator (`outcome=allowed` / `outcome=rejected`), which is what the
   console reads — so the dashboard shows **observed** traffic, not the configured limit.

---

## 3. Policies

| Route | Method | Limit | Window | Identity | Redis failure mode |
|-------|--------|-------|--------|----------|--------------------|
| `/api/products` | GET | 100 | 1 minute | Client IP | **Fail open** |
| `/api/login` | POST | 10 | 1 minute | Client IP | **Fail closed** |
| `/api/orders` | POST | 30 | 1 minute | Authenticated user | Fail open |

The split is the interesting part. A read-heavy catalogue route fails **open** — losing Redis costs
you a counter, not availability. A credential route fails **closed** — a lost Redis must never
become unlimited login attempts. That asymmetry is the whole argument for per-policy failure modes,
and it is why one global setting would be wrong here.

Four scopes are supported: **ENDPOINT** (per route), **IP** (per client IP), **USER** (per authenticated
principal), and **GLOBAL** / **APPLICATION** (one shared quota across all in-scope API routes, regardless
of IP or user). `GLOBAL` is the legacy name; `APPLICATION` is the same behavior with a clearer label.
Existing `GLOBAL` policies keep working unchanged.

**Policy groups** (Phase 4) let an admin compose multiple scope rules on a single endpoint. A group
has an `enabled` flag, a list of endpoints (each with method, path, display name, `repeatable`,
`exempt`), and each endpoint carries one or more `ScopeRule` (scope, algorithm, parameters). Groups
are validated against global rules and other groups via `PolicyGroupValidator`; projections are
materialized as individual `PolicyDocument` records in Redis so the enforcement path is unchanged.
The admin UI provides a group editor with add/remove endpoint and scope-rule rows, and the request
tester lets you select a group endpoint directly.

**Ids are server-generated and immutable.** A group id is the lowercase slug of its name (max 55
characters, numeric suffix on collision, e.g. `qa-orders-2`); an endpoint id is `ep-xxxxxxxx`. On
CREATE a caller may pin a group or endpoint id only if it matches `^[a-z0-9][a-z0-9-]{0,62}$`. On
update, an endpoint id that does not belong to the group returns 400 "cannot be changed"; an endpoint
without an id is treated as new. Overlapping routes (same path ignoring trailing slash, same method or
`ANY`) are rejected with 400 `policy_invalid`, within a group and across groups, but only for routes
the request adds or moves. Malformed rule values (window, scope, algorithm, refillInterval,
leaseDuration) return 400 `policy_invalid` with a message instead of 500. `GET /policies` rows carry
`source` = `POLICY` | `GROUP` | `GLOBAL`.

**Exemptions** are separate rules, not zero-limit policies: the create-policy form has an
"Exclude this API from rate limiting" checkbox that stores a distinct exemption for the selected
method and path (own Redis namespace `ratelimit:exemption:v1`, managed under
`/api/admin/rate-limit/exemptions`). Matching requests bypass **all** rate-limit policies,
including GLOBAL/APPLICATION. `/**` and admin/actuator routes are rejected at validation.

**Overview reads the same live policies.** The policy table is built from the authenticated
managed-policies API, never from `application.yml` or a fixed catalog, and shows a loading,
error+retry or empty state instead of sample rows.

**The request demo offers real routes.** `GET /api/admin/rate-limit/demo-routes` is generated from
Spring's registered handler mappings: only methods annotated `@DemoCallable` appear, so a route in the
dropdown provably exists. Unannotated routes (admin, actuator, policy control) are absent by
construction; a route that exists but must not be replayed (`@DemoCallable(repeatable = false)`) is
listed with the reason. Each entry carries the policies that actually apply, resolved by
`PolicyMatcher.matching` — the same call the filter uses — so method rules, `ANY`, wildcards,
GLOBAL/APPLICATION scopes, the enabled flag and exemptions cannot disagree with enforcement. The
dropdown shows one option per route with its policy count, and each policy's own algorithm, scope and
parameters are listed separately because limits are not comparable across algorithms. Enabled
policies whose path matches no handler are reported separately: a policy does not prove an API
exists, and a mistyped path can never be offered as a live route.

**Request tester per-response inspection.** After a run, every response appears as a collapsible
entry with status badge, rate-limit headers, message, and pretty-printed JSON body. The runner is
bounded, sequential, cancellable, and reports first-429 index, elapsed time, and status breakdown.

---

## 4. Run it

### Prerequisites

Java 21, Maven 3.9, Node 20+, Docker (for Redis).

### Start and stop (Windows)

Run the repository script from PowerShell:

```powershell
.\start.bat
```

For this local POC, `start.bat` supplies `pocadmin` / `admin123` to the backend. These credentials
are intentionally demo-only and must not be used for a deployed or shared environment.

`start.bat` reuses the project's existing Redis container and data, builds the backend from current
source, starts the backend and Angular console in the background, waits for both health checks, and
opens the console. If the backend is already running, stop it first so an old JVM cannot keep serving
an earlier JAR.

```powershell
.\stop.bat
```

`stop.bat` stops the tracked backend/frontend processes and the project Redis container. It does not
delete the container or its data. Processes started manually are left alone unless they use the
legacy RateGuard window titles from the previous `start.bat`.

### 1. Redis

```powershell
docker run -d --name ratelimit-redis -p 6379:6379 redis:7-alpine
```

### 2. Backend

```powershell
cd redis-rate-limit-poc
mvn -B spring-boot:run
```

Listens on `8080`. Verify: `curl http://localhost:8080/actuator/health` → `UP`.

### 3. Console

```powershell
cd frontend
npm install
npm start
```

Opens on `4200` and proxies API calls to `8080` (see `frontend/proxy.conf.json`), so the browser
stays same-origin and there is no CORS configuration to get wrong.

The Policies workspace loads **capabilities**, **policies** and **exemptions** independently. A
failing section shows its own error with its own `Retry` button and never hides the sections that
did load, and the editor refuses to save a policy while algorithm/scope options are missing.

### Verification and demo scripts

Run from `redis-rate-limit-poc/scripts/`:

| Script | What it does |
|--------|--------------|
| `verify-all.ps1` | Full gate: health, policy enforcement, failure modes, two-instance sharing, Actuator evidence |
| `load-demo.ps1` | Sends a burst through one instance and reports PASS / INCONCLUSIVE |
| `two-instance-demo.ps1` | Two JVMs against one Redis — the sharing proof |

Useful flags: `-SkipBuild` skips Maven, `-SkipUnitTests` skips the test phase. A plain
`.\verify-all.ps1` runs everything.

---

## 5. Screenshots

All captured from the running console via headless Chromium. They are real runs, not mockups.

### 5.1 Dashboard overview — live counters from Actuator

![Dashboard overview](docs/screenshots/01-dashboard-overview.png)

`Allowed 200`, `Rejected 100`, `Active policies 3` — these are read from the Actuator counters after
the demo traffic, which is why they exceed the per-window limit of 100.

### 5.2 Service and Redis health

![Service and Redis health](docs/screenshots/02-service-and-redis-health.png)

Overall Actuator health is `UP`, including the Redis indicator.

### 5.3 Configured policies

![Rate limit policies](docs/screenshots/03-rate-limit-policies.png)

### 5.4 Request demo — before

![Request demo before run](docs/screenshots/04-request-demo-before-run.png)

### 5.5 Request demo — the limit hits

![Request demo 429 results](docs/screenshots/05-request-demo-429-results.png)

150 requests to `GET /api/products`: **100 allowed (2xx)**, **50 rejected (429)**, first 429 at
request **#101**, elapsed 1.9 s. The console compares the observed first rejection against the
configured limit and reports the match explicitly.

### 5.6 Response inspector — what a rejection looks like on the wire

![Response inspector headers](docs/screenshots/06-response-inspector-headers.png)

`HTTP 429`, `Retry-After: 59 s`, `X-RateLimit-Limit: 100`, `X-RateLimit-Remaining: 0`,
`X-RateLimit-Policy: products-read`.

### 5.7 Mobile layout

![Dashboard mobile layout](docs/screenshots/07-dashboard-mobile-layout.png)

### 5.8 Two-instance proof

![Two instance demo results](docs/screenshots/08-two-instance-demo-results.png)

30 requests to each of two JVMs (ports 18081 and 18082, pids 18120 / 7364). Result: **30 × HTTP 200**
across both instances combined with **0** rejections, because 60 stays under the 100 limit — and one
Redis key holds the **shared** count of 60 with `pttl 17760`. That single key is the evidence the
limit is not per-process.

---

## 6. Verification run

Everything below was executed against the code in this repository.

### Backend — `mvn -B clean verify`

```
Tests run: 153, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS in ~60 s
```

Testcontainers supplies a real Redis, so the fixed-window logic is tested against Redis rather than
a mock. Java 21.0.12.1, Maven 3.9.16.

### Frontend — `npm test`

```
Test Suites: 11 passed, 11 total
Tests:       84 passed, 84 total
Time:        ~8 s
```

### Frontend — `npm run build`

```
Initial total: 452.52 kB │ Transfer: 111.38 kB
Build at: ~12 s (application bundle)
```

### End-to-end gate — `verify-all.ps1 -SkipBuild -SkipUnitTests`

Took **133.50 s**. Sent 120 requests against `/api/products` and 45 against `/api/orders`; observed
first rejections at **#101** and **#31**, matching the configured 100 and 30 limits. Both routes
**PASS**.

### Two-instance demo — `two-instance-demo.ps1`

89.3 s, all expectations met — see screenshot 5.8.

---

## 7. Layout

```
.
├── redis-rate-limit-poc/        Spring Boot backend
│   ├── src/main/java/…          interceptor, policies, endpoints, health
│   ├── src/main/resources/      application.yml
│   ├── src/test/java/…          Testcontainers-backed tests
│   ├── scripts/                 verify-all · load-demo · two-instance-demo
│   ├── docs/                    deep design doc
│   └── README.md
├── frontend/                    Angular 22 console
│   ├── src/app/core/            API client, config, demo runner, models
│   ├── src/app/features/        dashboard · request-demo
│   └── proxy.conf.json
└── docs/
    ├── screenshots/             the 8 images above
    └── reports/                 PDF report
```

The Angular app sits at the repository root as a sibling of the Maven module, not nested inside it.
`docs/screenshots` and `docs/reports` are new; the screenshots and PDF report are the only
documentation added by the reporting task.

---

## 8. Known limits

Stated plainly, because a POC that hides these is not a POC:

- **Single-node Redis.** The counter is shared correctly, but there is no Redis Cluster or Sentinel
  failover. If Redis dies, the configured failure mode applies — it does not recover.
- **Fixed window boundaries.** A fixed-window policy still allows roughly **2× the limit across
  a window boundary**. Exact sliding-window and token-bucket policies are available per route for
  cases where that matters; the sliding-window counter is approximate by design.
- **Client IP by `X-Forwarded-For`.** Behind a proxy this needs a trusted-proxy list. Fine for a POC,
  not for production.
- **No persistence of rejections.** Blocked requests are counted in Actuator, not written anywhere
  durable. A real system would want an audit trail and alerting on the rejected counter.
- **In-memory Actuator counters.** They reset with the process, which is why the console shows
  session-scoped totals labelled "browser-observed since this page opened".

### Limitations: control-plane protection (`rate-limit.admin-protection`)

- **Identity is the client IP only.** It is the socket peer unless `rate-limit.trusted-proxies` lists
  the proxies you operate. Clients behind a shared NAT or an untrusted proxy therefore share one
  counter: anyone there can trip the 300/min request cap, or lock the admin out of that address for
  5 minutes with 10 bad logins, and repeat it indefinitely.
- **Fails closed.** If Redis is down, any request carrying an `Authorization` header gets 503, on public
  routes too, because the lockout cannot be checked. Requests without credentials are unaffected.
- **Production should** key failed logins on IP + username and configure `trusted-proxies`.
- `X-Forwarded-For` / `X-Real-IP` are not spoofable here: they are ignored unless the socket peer is a
  trusted proxy, and only the rightmost untrusted hop is used.

---

## 9. Documentation map

| Document | Audience |
|----------|----------|
| This README | Start here — architecture, how to run, evidence |
| [`redis-rate-limit-poc/docs/api-rate-limiting-poc.md`](redis-rate-limit-poc/docs/api-rate-limiting-poc.md) | Deep design: algorithm, policy model, failure modes, test strategy |
| [`redis-rate-limit-poc/README.md`](redis-rate-limit-poc/README.md) | Backend-specific commands |
| [`docs/reports/rate-limiting-poc-report.md`](docs/reports/rate-limiting-poc-report.md) | This README as a standalone report, with the screenshots |
| [`docs/reports/`](docs/reports/) | PDF report — same content, printable |
| Screenshots | `docs/screenshots/`, referenced inline above |
