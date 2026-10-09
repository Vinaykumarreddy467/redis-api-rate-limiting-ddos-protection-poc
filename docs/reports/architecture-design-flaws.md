# Architecture and Design Flaws: Rate-Limiting / DDoS POC

Reviewed: 2026-10-09. Companion to `code-review-findings.md` (line-level bugs). This file covers structural problems.
**Evidence level:** *Read* means I read the code. *Skimmed* means I read only part of it (`ManagedPolicyStore`, `PolicyAdminController`, `DemoRouteCatalog` were grepped, not read end to end). *Inferred* means it follows from the design but I haven't run it.

## Headline

The project is titled "DDoS Protection", but what it builds is **application-tier, per-request rate limiting with Redis in the hot path**. That is a good API-quota tool. It is not DDoS protection, and the architecture has several properties that make it *worse* than no limiter under a real flood (A1, A2, A4).

---

## A. Wrong layer, wrong failure behaviour

### A1. The limiter lives inside the application it protects (Read)
It is a servlet filter after Spring Security (`RateLimitConfiguration`). By the time it runs, the TCP and TLS connection is open, a Tomcat thread is held, the request is parsed, authentication has been attempted, and policy lookups have hit Redis. Rejection is therefore as expensive as most of the work it is meant to prevent. Real DDoS defence belongs at the edge (CDN/WAF/LB/NGINX `limit_req`, or a gateway) with this app as the second, business-aware layer.
Consequences: no L3/L4/slowloris/connection-count/request-size protection, and a flood can exhaust threads even when every request is correctly answered 429.

### A2. Redis is a synchronous dependency of every request, with no local fast path (Read + Inferred)
- Roughly 6 sequential Redis calls per request (detail in findings #4), each with a 2s timeout.
- There is no in-process L1 layer: no local token reservation or batching, no short-lived local deny cache for an identity that was just blocked. A client hammering at 10k rps costs 10k Redis round trips *while being rejected*.
- No circuit breaker or bulkhead: during an outage every request waits on timeouts, so the limiter turns a Redis incident into an application-wide latency incident.
A resilient design would use a local approximate limiter as the fallback and reconcile with Redis asynchronously, instead of choosing only between fail-open and fail-closed.

### A3. Failure semantics are binary and inconsistent (Read)
Outage behaviour is "no limit at all" (fail-open default) or "503 for everyone" (fail-closed). Worse, the managed policy source silently degrades to the YAML baseline during an outage (`PolicyMatcher.effectivePolicies`), so the *rules themselves change* when Redis is down: admin edits stop applying, and a rule an admin tightened reverts to the looser one. The comment documents this, but it is a design choice that makes behaviour during incidents unpredictable.

### A4. Single Redis, single point of failure, and not cluster-compatible by construction (Read)
`docker-compose.yml` runs one Redis, no replica or Sentinel. The batch Lua script touches keys for *different policies and identities* in one `EVAL`. Those keys hash to different slots, so it **cannot run on Redis Cluster** without a redesign. The Javadoc admits "single Redis only". That is a hard scalability ceiling: one Redis core handles all rate-limit decisions for all instances, and the script does per-policy `cjson.decode` on every call.

---

## B. Data-plane / control-plane coupling

### B1. Policies and counters share one Redis keyspace and instance (Read)
Admin-edited policy documents, audit log and exemptions live next to millions of ephemeral counter keys. `docker-compose.yml` sets no `maxmemory` or eviction policy. If someone adds `maxmemory` with an `allkeys-*` policy (the usual production advice), **policy documents can be evicted under a key flood**, and a flood is precisely the scenario this system exists for. Control-plane state needs its own database, instance or eviction guarantee (`volatile-*` with TTL only on counters).

### B2. Policy distribution is pull-per-request, not push-and-cache (Read)
Every request re-reads and re-parses all policies, groups, global rules and exemptions. There is already an `epoch` counter in the store that could drive cache invalidation, but it isn't used on the request path. This is both the main performance flaw and an amplification vector: attacker cost is one cheap request, defender cost is several Redis calls plus JSON deserialisation.

### B3. Control plane and demo/data plane in one deployable (Read)
`DemoController`, `DemoCallable`, `DemoRouteCatalog`, the admin API and the limiter are one Spring Boot app. The admin API is exempt from limiting (so the control plane can't be throttled out) but shares threads, Redis connections and the 16-connection Lettuce pool with the traffic it governs. A flood can starve admin operations, which is exactly when an admin needs them.

---

## C. Domain model

### C1. Three overlapping policy models with compatibility projections (Read)
`RateLimitProperties.Policy` (YAML), `PolicyDocument` (flat per-policy record) and `PolicyGroup` / `EndpointRule` / `ScopeRule` / `GlobalScopeRules`, joined by `ProjectionService` generating hashed ids (`p-` + sha256), plus `groupOwned` de-duplication logic in `PolicyMatcher`. There are multiple sources of truth and a layer that exists only so old and new models agree. The admin controller's `/policies/{id}` endpoint is overloaded to mean "a group, a global rule, a projected policy or a plain policy" depending on the id. This is the shape of an unfinished migration; every feature must now be implemented across 3 representations.

### C2. `Scope` conflates two orthogonal ideas (Read)
`Scope` has IP, USER, ENDPOINT, APPLICATION, GLOBAL. IP and USER are *who is being counted* (identity dimension); ENDPOINT, APPLICATION and GLOBAL are *how widely quotas aggregate* (aggregation level). They are one enum, so you cannot express "per-user across the whole application" or "per-IP per-endpoint" as independent choices, and the filter special-cases GLOBAL/APPLICATION with constant identities (`"global"`, `"application"`).

### C3. The "hierarchy" is validated, not enforced, and compares incomparable things (Read)
`PolicyGroupValidator` checks that child RPS is strictly less than parent RPS, but `normalizedRps()` compares window counts, token-bucket refill rates and leaky-bucket drain rates as if they were equivalent, and returns `NaN` for concurrency, so those rules silently skip validation. At runtime all rules are simply AND-ed; nothing enforces or even uses the hierarchy. It is a lint presented as a guarantee, and the ENDPOINT < IP direction is questionable (findings #9).

### C4. "Flat nullable record" makes invalid states representable (Read)
`PolicyDocument` has ~20 nullable fields where only some are meaningful per algorithm, guarded by a runtime `validate()`. The `Algorithm` type should own its parameters (a sealed interface with one record per algorithm). The current shape is why bugs like token-bucket `cost > capacity` and the mislabelled `describeParameters()` exist.

### C5. Policy edits do not version counter state (Read, partly Inferred)
Redis keys include `policy.id` but not the policy version or parameters. Editing a token bucket's `capacity`/`refillInterval` or a leaky bucket's `drainRate` reinterprets existing live state under new parameters. The Lua even carries legacy-format migration code (`legacyDepth`) in the hot path to cope with an earlier state format. State migration is being done in the request path instead of by key versioning.

---

## D. Algorithm engine

### D1. One monolithic Lua script (Read)
~170 lines doing six algorithms in two phases (check, then charge) with duplicated per-algorithm logic in both phases and JSON arguments decoded by `cjson` per call. It can't be unit-tested without Redis, and phase-1/phase-2 divergence is a standing source of bugs (the sliding-counter `Remaining` mismatch is one). A script per algorithm composed by a thin dispatcher, or one `redis.call`-free pure function tested in isolation, would be safer.

### D2. Concurrency limiting is bolted onto a request-counting model (Read)
Permits are held across the request lifecycle and released in a servlet `finally` or an async listener, with lease semantics stored in a SET with a set-level TTL (findings #5). A counted-request limiter and a lease-based in-flight limiter have different failure models (leaks, crashed holders, heartbeat) but share one interface, one script and one result shape.

### D3. Dead legacy path retained (Read)
`RateLimitStore.consume/peek`, the default `consumeAll`, `fixedProjection` and `RateLimitPolicyResolver` implement an older single-policy design. They keep a second code path alive and let tests exercise code that production doesn't use.

---

## E. Identity and security architecture

### E1. Only two identity types, and the fallback is lossy (Read)
IP (with XFF parsing) or Basic-auth principal. No API key, JWT subject, tenant or client-id dimension (API-key support was removed in an earlier commit), so a multi-tenant or shared-NAT deployment can't be modelled: one noisy corporate NAT blocks everyone behind it, while IPv6 users can evade entirely (findings #6). USER falling back to IP means a user's quota depends on whether their auth succeeded.

### E2. Authentication is entangled with limiting order (Read)
Because the limiter must follow Security to learn the user, it cannot protect Security itself (findings #1). The architecture needs two stages: a pre-auth IP/failed-attempt limiter, then a post-auth user limiter.

### E3. Admin security model is thin (Read)
Single shared administrator from env vars, in-memory user store, HTTP Basic on every call, credentials held in browser memory as a reusable `Authorization` header, no lockout, no MFA, no per-admin accounts (the audit trail's "actor" is the one shared name), no TLS configured in the repo. Appropriate for a POC; a blocker for anything else.

### E4. The 429 response discloses policy internals (Read)
The body returns `policy`, `algorithm`, `scope`, `limit`, `windowSeconds` and `consultedPolicies` (all matched policy ids) to any caller. That's a free map of your defences for an attacker tuning a bypass.

---

## F. Operability and observability

### F1. Metrics are per-instance and consumed via actuator internals (Read)
Counters are in-memory Micrometer meters per JVM. The dashboard reads `/actuator/metrics/ratelimit.requests?tag=...` directly, so the UI shows one instance's numbers, not the cluster's (inferred from how the frontend calls it). There is no latency, no per-identity top-talkers, no alerting hook, and `/actuator/**` is publicly readable.

### F2. No blocking or escalation model (Read)
There's no temporary ban, penalty escalation, blocklist/allowlist by CIDR (exemptions are path-based only), or challenge response. Every rejected request still costs full filter work, and persistent offenders get no stricter treatment. For a project billed as DDoS protection this is the biggest functional gap.

### F3. Operations tooling is Windows-only and local-only (Read)
Start/stop is `.bat` + PowerShell; no Dockerfile for the app, no CI, no deployment manifests, no container health wiring beyond Redis. The frontend is only wired through the Angular dev-server proxy (`proxy.conf.json`); `api-config.ts` assumes same-origin in production but nothing builds or serves that.

---

## G. Code organisation (Skimmed)

- God classes: `PolicyAdminController` (~900 lines, with domain logic such as projection-id resolution and group/global/rule dispatch), `ManagedPolicyStore` (~770 lines: Lua, serialisation, audit, seeding, repair). Business rules live in the web layer.
- Redis persistence classes sit in the `policy` domain package alongside the model, with no repository interface, which is why tests need real Redis for most things.
- Two independent exemption systems (`ExemptionStore` documents and endpoint-level `exempt` flags inside groups) evaluated by two code paths in the filter.
- Policy matching is duplicated (`PolicyMatcher` vs `PolicyEnforcer.select`) with different ordering rules.

---

## Recommended target architecture (short)

1. **Edge layer** for volumetric/connection limits; this app becomes the business-quota tier.
2. **Two-stage limiting:** cheap pre-auth IP and failed-auth limiter, then post-auth user/tenant limiter.
3. **In-memory policy snapshot** refreshed by epoch/pubsub; hot path = one Redis call (or zero, with a local token-reservation cache).
4. **Circuit breaker + local fallback limiter** instead of binary fail-open/fail-closed.
5. **Separate Redis (or logical store with a no-evict guarantee) for policies** from counters; Sentinel/replica for HA; hash-tag or per-key scripts if Cluster is ever required.
6. **One policy model** (sealed type per algorithm, identity and aggregation as separate fields) with the YAML only as a seed; delete projections and the legacy path.
7. **Versioned counter keys** (include policy version or parameter hash) so edits start clean without in-script migration.
8. **Escalation:** temp bans, CIDR allow/deny, progressive penalties.
9. Cluster-wide metrics (Redis-derived or a metrics backend), and sanitised 429 bodies.

## Priority

| Priority | Item |
|---|---|
| Do first | A2/B2 (policy cache + circuit breaker), E2 (pre-auth limiter), A1 (put an edge limiter in the story) |
| Next | C1/C2/C4 (collapse the model), B1 (Redis separation), D2 (lease redesign) |
| Later | A4 (HA/Cluster), F1/F2 (observability, bans), F3 (CI, containers) |
