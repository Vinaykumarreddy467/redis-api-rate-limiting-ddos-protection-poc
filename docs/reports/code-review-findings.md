# Rate-Limiting POC: Code Review Findings

Reviewed: 2026-10-09. Static read of the backend source (`redis-rate-limit-poc/src/main`) and the frontend auth/API layer.
**Not done:** the app was not run, tests were not executed, and logs, temp files, screenshots and `graphify-out/` were deliberately not read.
Each finding says how confident I am. "Confirmed by reading" means the code path is unambiguous. "Needs a test" means I'm inferring runtime behaviour.

## Summary

| # | Severity | Area | Finding |
|---|----------|------|---------|
| 1 | High | Security | Failed Basic-auth attempts are never rate limited (limiter runs after Spring Security), and `/api/admin/**` is exempt, so passwords can be brute-forced |
| 2 | High | Correctness | Fail-closed policy is ignored when a fail-open policy is also in the composition |
| 3 | High | DDoS bypass | `HEAD` is excluded from limiting but is served by the `GET` handlers |
| 4 | High | Availability | Several uncached Redis round trips per request, each with a 2s timeout, so a Redis outage stalls every request for ~8s |
| 5 | Medium | Correctness | Concurrency permits can leak permanently (set-level TTL is refreshed on every acquire) and can expire while a long request is still running |
| 6 | Medium | DDoS bypass | IPv6 clients are limited per full address, so a /64 gives an attacker effectively unlimited identities |
| 7 | Medium | Correctness | Token bucket accepts `cost > capacity`, giving a policy that can never allow a request |
| 8 | Medium | Correctness | `X-RateLimit-Remaining` is overstated for the sliding-window counter |
| 9 | Medium | Design | Hierarchy validator requires ENDPOINT < IP, which makes the per-IP rule unreachable |
| 10 | Low | Correctness | Fixed-window `Retry-After` includes the TTL grace, so it is up to 1s too long |
| 11 | Low | Correctness | Concurrency release is not atomic (remove, size, delete) and can delete a lease acquired in between |
| 12 | Low | UI | `describeParameters()` mislabels token-bucket refill (shows `cost`, actual refill is `capacity`) |
| 13 | Low | Security | IPv6 zone-id (`%`) passes the literal check despite the comment saying it is rejected |
| 14 | Low | Frontend | `btoa()` throws on non-Latin-1 passwords (admin login and demo request) |
| 15 | Low | Hygiene | Hardcoded demo credentials, unbounded `limit` on `/audit`, public `/actuator/metrics`, `limit`/`capacity` have no upper bound |
| 16 | Info | Maintainability | Dead legacy fixed-window path, stale comments, duplicated matching logic, committed junk files |

---

## High

### 1. Brute force is not rate limited: auth failures never reach the limiter
**Files:** `config/RateLimitConfiguration.java:191-197`, `web/SecurityConfig.java:38-45`, `ratelimit/RateLimitFilter.java:90`
*Confirmed by reading.*

The limiter is registered at `LOWEST_PRECEDENCE - 100`, after the Spring Security chain. A request with bad Basic credentials gets a 401 from Security and never reaches `RateLimitFilter`. So:
- `/api/orders` has a USER-scoped policy, but password guessing against it (401s) is never counted.
- `/api/admin/**` is explicitly skipped by `shouldNotFilter` (`path.startsWith("/api/admin/")`), so even requests that reach the limiter are unthrottled. The admin password is the only thing protecting the control plane, and guessing it is free.
- The `login-attempt` policy protects only `/api/login`, which is a stub and authenticates nobody.

**Fix:** Add a failed-auth limiter ahead of Security (an IP-keyed filter, or an `AuthenticationFailureBadCredentialsEvent` listener that increments a counter). Rate limit `/api/admin/**` per IP for *failures*, not for successes. Don't exempt it wholesale.

### 2. `FAIL_CLOSED` is silently overridden by the last policy in the list
**File:** `ratelimit/RateLimitFilter.java:136-170`
*Confirmed by reading.*

`activePolicy` is reassigned on every loop iteration, so after the loop it is the **last** applicable policy. Route-scoped policies sort first and global policies last. In the `catch (RateLimitStoreUnavailableException)` block, `failureModeFor(governing)` uses that last policy.
Example: `/api/login` has `login-attempt` (fail_closed) plus a managed GLOBAL rule (default fail_open). Redis goes down, the governing policy is the global one, and the login request **passes through**. This defeats the "credential endpoints fail closed" guarantee in `application.yml`.

**Fix:** Fail closed if *any* applicable policy is fail-closed: `applicable.stream().anyMatch(p -> failureModeFor(p) == FAIL_CLOSED)`.

### 3. `HEAD` requests bypass all limits
**Files:** `application.yml` (`excluded-methods: OPTIONS, HEAD`), `RateLimitFilter.shouldNotFilter`
*Confirmed by reading; behaviour of Spring MVC mapping `HEAD` to `@GetMapping` is standard.*

Spring MVC serves `HEAD` through the `GET` handler (the body is dropped afterwards). Any attacker can send unlimited `HEAD /api/products` and the full handler cost is incurred with zero quota use. The same applies to every future GET endpoint.

**Fix:** Don't exclude `HEAD`; charge it to the matching `GET` policy. Excluding only `OPTIONS` (preflight) is fine.

### 4. Per-request Redis chatter, and an outage multiplies latency
**Files:** `RateLimitFilter.doFilterInternal`, `ExemptionStore.findAll`, `EndpointExemptionService.isExempt`, `PolicyMatcher.effectivePolicies`
*Confirmed by reading the call sequence; the timeout impact needs a load test.*

Every rate-limited request does, sequentially: (1) exemptions `findAll` (Lua + JSON parse of every doc), (2) `findAllGroups` for endpoint exemptions, (3) `findAllGroups` **again** in the matcher, (4) `findGlobalRules`, (5) `store.findAll`, (6) the batch Lua. That is roughly 6 round trips and a full JSON deserialisation of all policies per request, with no cache. This is itself a DoS amplifier: each cheap attacker request costs several Redis calls.
During a Redis outage, steps 1-3 and 6 each wait up to the configured 2s timeout (`application.yml`: `timeout: 2s`), so every request is delayed ~8s before the fail-open/fail-closed decision. Tomcat threads fill up quickly.

**Fix:** Cache the policy/exemption snapshot in memory with a short TTL (or pub/sub or version-epoch invalidation, since an `epoch` key already exists). Collapse to one Redis call per request on the hot path. Add a circuit breaker so a known-down Redis fails fast.

---

## Medium

### 5. Concurrency-limit permits leak and expire incorrectly
**File:** `RedisRateLimitStore.java`, BATCH script algo 6
*Confirmed by reading.*

All leases live in one Redis SET and `PEXPIRE key leaseMs` is refreshed on **every** acquire. Individual members never expire.
- **Leak:** if an instance crashes mid-request, its member stays in the set. As long as any traffic acquires within `leaseMs`, the key never expires, so the permit is leaked until traffic stops. Under steady load, `maxConc` slowly drains to zero and the endpoint locks everyone out (a self-inflicted DoS).
- **Early expiry:** a request that runs longer than `leaseMs` with no other traffic loses its permit when the key expires, so the cap is exceeded.

**Fix:** Use a ZSET scored by expiry time. On each acquire, `ZREMRANGEBYSCORE key 0 now` first, then count. Each lease then has its own deadline.

### 6. IPv6 clients can mint unlimited identities
**File:** `RateLimitIdentityResolver.canonicalIp`
*Confirmed by reading.*

IPv6 is keyed on the full literal. Any host with a /64 (the normal home or ISP allocation) can rotate through 2^64 source addresses and never hit an IP limit. IPv4-mapped addresses are normalised; native IPv6 is not aggregated. The comment says one spelling per address is "unique", which is true but not the right granularity.

**Fix:** Key IPv6 on its /64 (or /56) prefix.

### 7. Token bucket allows `cost > capacity`
**Files:** `PolicyDocument.validate()`, Lua algo 4
*Confirmed by reading.*

Validation only checks `cost >= 1`. If `cost > capacity`, tokens are capped at `capacity`, so `tokens < cost` is always true. Every request is denied forever, and `Retry-After` is computed from a wait that can never succeed.

**Fix:** Validate `cost <= capacity`.

### 8. `X-RateLimit-Remaining` is wrong for sliding-window counter
**File:** BATCH Lua algo 3
*Confirmed by reading.*

`govRemaining = limit - count`, where `count` is only the current window's counter. The admission test uses the weighted estimate `cur + prev * (1 - elapsed/window)`. Early in a window `prev` carries heavy weight, so the header reports plenty remaining while the next request is rejected. Clients that trust the header get surprise 429s.

**Fix:** Report `limit - ceil(est + 1)`, using the same estimate as the admission test.

### 9. Hierarchy validation makes the IP rule dead
**File:** `PolicyGroupValidator.validateEndpointHierarchy`
*Confirmed by reading; intent should be confirmed with the author.*

It requires `ENDPOINT RPS < IP RPS`. An ENDPOINT rule is an aggregate across all callers. If the endpoint-wide cap is strictly lower than the per-IP cap, no single IP can ever reach its own limit, so the IP rule never fires. The same applies to USER. The other checks (IP < APPLICATION < GLOBAL) are the sensible direction. There is also no ENDPOINT-vs-APPLICATION or USER-vs-APPLICATION check.

---

## Low

10. **`Retry-After` overshoots (fixed window).** The deny path in Lua uses `PTTL`, which includes `graceMs` (default 1s), so clients are told to wait up to 1s longer than the actual window (`RedisRateLimitStore.java`, BATCH algo 1).
11. **Non-atomic lease release.** `releaseConcurrency` does `SREM`, then `SCARD`, then `DEL`. If another request `SADD`s between the `SCARD` and the `DEL`, its fresh lease is deleted. Use a single Lua script.
12. **Token-bucket description is wrong.** `PolicyDocument.describeParameters()` prints `refill <cost> per <interval>`, but the Lua refills the whole `capacity` per `refillInterval` and `cost` is the per-request charge. The admin UI and audit records mislead.
13. **Zone-id not actually rejected.** `CidrBlock.LITERAL` is `[0-9a-fA-F:.%]+`, so `fe80::1%x` passes `parseLiteral` and is used as an identity, which contradicts the Javadoc. Only reachable via a header written by a trusted proxy, so low impact. Drop `%` from the pattern.
14. **`btoa()` with non-Latin-1 input throws.** `admin-api.service.ts:45,189` and `demo-request.service.ts:118` build Basic auth with `btoa(user:password)`. A password containing e.g. `é€` or any emoji raises `InvalidCharacterError`. Encode via UTF-8 bytes first.
15. **Hygiene and hardening:**
    - `alice-pw` / `bob-pw` are hardcoded in `SecurityConfig` (acceptable for a POC, but never in a deployed build).
    - `GET /api/admin/rate-limit/audit?limit=` has no upper bound.
    - `/actuator/metrics` is `permitAll` and exposes policy ids and outcome counts.
    - There is no max for `limit`/`capacity`/`queueCapacity`, so a sliding-log policy with a huge `limit` can hold that many ZSET entries per identity.
    - The leaky bucket takes an integer `drainRate`, so rates below 1 req/s cannot be expressed.
    - The `Retry-After` header is whole seconds only, so sub-second waits round up to 1s.

## Info / maintainability

- The legacy fixed-window path (`RateLimitStore.consume`/`peek`, the default `consumeAll`, `fixedProjection`, `RateLimitPolicyResolver`) appears unused in production and duplicates logic. Remove it or mark it test-only.
- Policy matching is implemented twice, in `PolicyMatcher.matching` and `PolicyEnforcer.select`, with *different* sort orders (specificity comparator vs path length). Tests using the explicit-policy seam can therefore diverge from production.
- `api-client.service.ts` still says "there is no policy mutation API", which is stale now that the admin API exists.
- `AdminProperties` uses prefix `ratelimit.admin` while everything else uses `rate-limit.*`. It probably binds through relaxed binding, but it is inconsistent and worth unifying.
- `docker-compose.yml` publishes Redis `6379` on all interfaces with no `requirepass`. Bind to `127.0.0.1` for anything beyond a laptop.
- Repo hygiene: tracked and untracked junk (`frontend/tempenv.txt`, `frontend/testout.txt`, `frontend/logs/console.err` at ~240 KB, `redis-rate-limit-poc/logs/backend.err`, `redis-rate-limit-poc/tmp_diff.txt`, `.agents/mcp_config.json`, root `package.json` / `package-lock.json`, stray screenshots). Add them to `.gitignore` and `git rm --cached` the tracked ones. Note `*.log` is ignored but `*.err` is not.

## What looks solid

- Single-script atomic check-then-charge across all policies, so a denial charges nothing.
- Time comes from Redis `TIME`, so JVM clock skew is not an issue.
- Strict XFF handling: right-to-left walk, trusted-proxy gating, and a strict IPv4 literal regex.
- The admin account has no default password, so it is locked unless configured.
- Optimistic concurrency (version compare-and-set) on policy edits.
- The access log excludes query strings and auth headers.

## Suggested fix order

1. #2 (small, one-line logic change) and #3 (config change).
2. #1 (failed-auth throttling).
3. #4 (policy snapshot cache plus fail-fast), which also reduces the blast radius of every Redis outage.
4. #5 (ZSET leases), then #6 through #8.

I can implement any of these and add regression tests (the existing `RateLimitRedisFailureTest` and `RedisRateLimitStoreTest` are good places for #2 and #5).
