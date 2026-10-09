package com.example.ratelimit.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed rate-limit state, decided entirely inside one Lua script.
 *
 * <p>All policy decisions run in Redis Lua, so concurrent app instances share atomic state changes.
 * Each algorithm assigns a TTL to its state. Fixed window means a client may fire up to 2x the
 * limit across a window boundary; that is accepted and documented for this POC.
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {

    /** Returns {count, pttlMillis}. ARGV[1] = ttlMillis. */
    private static final RedisScript<List> CONSUME = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """, List.class);

    /** Read-only counterpart: returns {count, pttlMillis} without creating or changing quota state. */
    /**
     * Control-plane failed-login tracker. KEYS[1]=failure counter, KEYS[2]=lock key;
     * ARGV: max failures, counting window ms, lockout seconds. Returns 1 when this failure triggered a lock.
     * The counter TTL is set in the same script as the INCR, and the lock lasts the full lockout.
     */
    private static final RedisScript<Long> CP_FAILURE = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if c >= tonumber(ARGV[1]) then
              redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<List> PEEK = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            local count = 0
            if raw then
              count = tonumber(raw)
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    /**
     * Atomic multi-algorithm batch. KEYS[i] is the state key for charge i; ARGV[i] is a JSON object
     * with {algo, limit, windowMs, graceMs, capacity, refillMs, cost, drainRate, queueCap, maxConc,
     * leaseMs, member}.
     *
     * <p>Algorithm ids: 1 fixed window, 2 exact sliding log, 3 sliding-window counter, 4 token bucket,
     * 5 leaky-bucket policing, 6 concurrency leases.
     *
     * <p>Phase one inspects every charge without spending quota and returns early on the first denial
     * as {@code {0, blockedIndex1Based, retrySeconds, blockedLimit}}. Trimming already-expired
     * sliding-log entries is the only phase-one write, and it cannot change any decision. Phase two
     * applies every write only when all charges allow, returning {@code {1, govLimit, govRemaining}}.
     *
     * <p>Time comes from Redis TIME, so JVM clocks never disagree about windows, refills or retries.
     *
     * <p>Single Redis instance only. The keys of one batch would have to share a hash slot (hash tags)
     * under Redis Cluster; this POC runs one Redis, so no tagging is applied and no cross-slot claim
     * is made.
     */
    private static final RedisScript<List> BATCH = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local nowms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local n = #KEYS
            for i = 1, n do
              local p = cjson.decode(ARGV[i])
              if p.algo == 1 then
                local raw = redis.call('GET', KEYS[i])
                local count = raw and tonumber(raw) or 0
                if count >= p.limit then
                  local ttl = redis.call('PTTL', KEYS[i])
                  if ttl < 0 then ttl = p.windowMs end
                  return {0, i, math.max(1, math.ceil(ttl / 1000)), p.limit}
                end
              elseif p.algo == 2 then
                redis.call('ZREMRANGEBYSCORE', KEYS[i], 0, nowms - p.windowMs)
                local count = redis.call('ZCARD', KEYS[i])
                if count >= p.limit then
                  local oldest = redis.call('ZRANGE', KEYS[i], 0, 0, 'WITHSCORES')
                  local retry = p.windowMs
                  if #oldest >= 2 then retry = math.max(1, oldest[2] + p.windowMs - nowms) end
                  return {0, i, math.max(1, math.ceil(retry / 1000)), p.limit}
                end
              elseif p.algo == 3 then
                local idx = math.floor(nowms / p.windowMs)
                local cur = tonumber(redis.call('GET', KEYS[i] .. ':w' .. idx) or '0')
                local prev = tonumber(redis.call('GET', KEYS[i] .. ':w' .. (idx - 1)) or '0')
                local elapsed = nowms % p.windowMs
                local est = cur + prev * (1 - elapsed / p.windowMs)
                if est >= p.limit then
                  return {0, i, math.max(1, math.ceil((p.windowMs - elapsed) / 1000)), p.limit}
                end
              elseif p.algo == 4 then
                local h = redis.call('HMGET', KEYS[i], 'tok', 'ts')
                local tokens = tonumber(h[1]) or p.capacity
                local ts = tonumber(h[2]) or nowms
                local elapsed = math.max(0, nowms - ts)
                tokens = math.min(p.capacity, tokens + elapsed * p.capacity / p.refillMs)
                if tokens < p.cost then
                  local wait = (p.cost - tokens) * p.refillMs / p.capacity
                  return {0, i, math.max(1, math.ceil(wait / 1000)), p.capacity}
                end
              elseif p.algo == 5 then
                -- Water level is stored as milli-requests plus the last Redis timestamp. A legacy
                -- integer counter is interpreted conservatively during rollout: a full old bucket
                -- keeps its old expiry; a non-full one carries all its existing debt into v2.
                local raw = redis.call('GET', KEYS[i])
                local level = 0
                local last = nowms
                local legacyDepth = nil
                if raw then
                  local levelText, lastText = string.match(raw, '^(%d+):(%d+)$')
                  if levelText then
                    level = tonumber(levelText)
                    last = tonumber(lastText)
                  else
                    legacyDepth = tonumber(raw)
                    if not legacyDepth then
                      local badTtl = redis.call('PTTL', KEYS[i])
                      if badTtl < 0 then badTtl = p.windowMs end
                      return {0, i, math.max(1, math.ceil(badTtl / 1000)), p.queueCap}
                    end
                    if legacyDepth >= p.queueCap then
                      local ttl = redis.call('PTTL', KEYS[i])
                      if ttl < 0 then ttl = p.windowMs end
                      return {0, i, math.max(1, math.ceil(ttl / 1000)), p.queueCap}
                    end
                    level = legacyDepth * 1000
                  end
                end
                if legacyDepth == nil then
                  local elapsed = math.max(0, nowms - last)
                  level = math.max(0, level - elapsed * p.drainRate)
                end
                local capacity = p.queueCap * 1000
                if level + 1000 > capacity then
                  local waitMs = math.ceil((level + 1000 - capacity) / p.drainRate)
                  return {0, i, math.max(1, math.ceil(waitMs / 1000)), p.queueCap}
                end
              elseif p.algo == 6 then
                local held = redis.call('SCARD', KEYS[i])
                if held >= p.maxConc then
                  local ttl = redis.call('PTTL', KEYS[i])
                  if ttl < 0 then ttl = p.leaseMs end
                  return {0, i, math.max(1, math.ceil(ttl / 1000)), p.maxConc}
                end
              end
            end
            local govLimit = 0
            local govRemaining = 0
            for i = 1, n do
              local p = cjson.decode(ARGV[i])
              if p.algo == 1 then
                local count = redis.call('INCR', KEYS[i])
                local elapsed = nowms % p.windowMs
                local ttl = p.windowMs - elapsed + p.graceMs
                if count == 1 then redis.call('PEXPIRE', KEYS[i], ttl) end
                if redis.call('PTTL', KEYS[i]) < 0 then redis.call('PEXPIRE', KEYS[i], ttl) end
                if i == 1 then govLimit, govRemaining = p.limit, math.max(0, p.limit - count) end
              elseif p.algo == 2 then
                redis.call('ZADD', KEYS[i], nowms, p.member)
                redis.call('PEXPIRE', KEYS[i], p.windowMs + p.graceMs)
                if i == 1 then
                  local count = redis.call('ZCARD', KEYS[i])
                  govLimit, govRemaining = p.limit, math.max(0, p.limit - count)
                end
              elseif p.algo == 3 then
                local idx = math.floor(nowms / p.windowMs)
                local curKey = KEYS[i] .. ':w' .. idx
                local prevKey = KEYS[i] .. ':w' .. (idx - 1)
                local count = redis.call('INCR', curKey)
                redis.call('PEXPIRE', curKey, 2 * p.windowMs + p.graceMs)
                redis.call('PEXPIRE', prevKey, 2 * p.windowMs + p.graceMs)
                if i == 1 then govLimit, govRemaining = p.limit, math.max(0, p.limit - count) end
              elseif p.algo == 4 then
                local h = redis.call('HMGET', KEYS[i], 'tok', 'ts')
                local tokens = tonumber(h[1]) or p.capacity
                local ts = tonumber(h[2]) or nowms
                local elapsed = math.max(0, nowms - ts)
                tokens = math.min(p.capacity, tokens + elapsed * p.capacity / p.refillMs)
                tokens = tokens - p.cost
                redis.call('HSET', KEYS[i], 'tok', tokens, 'ts', nowms)
                redis.call('PEXPIRE', KEYS[i], 2 * p.refillMs + p.graceMs)
                if i == 1 then govLimit, govRemaining = p.capacity, math.max(0, math.floor(tokens)) end
              elseif p.algo == 5 then
                local raw = redis.call('GET', KEYS[i])
                local level = 0
                local last = nowms
                local legacyDepth = nil
                if raw then
                  local levelText, lastText = string.match(raw, '^(%d+):(%d+)$')
                  if levelText then
                    level = tonumber(levelText)
                    last = tonumber(lastText)
                  else
                    legacyDepth = tonumber(raw)
                    level = legacyDepth * 1000
                  end
                end
                if legacyDepth == nil then
                  local elapsed = math.max(0, nowms - last)
                  level = math.max(0, level - elapsed * p.drainRate)
                end
                level = level + 1000
                local ttl = math.max(1, math.ceil(level / p.drainRate)) + p.graceMs
                local stored = string.format('%.0f:%.0f', level, nowms)
                redis.call('SET', KEYS[i], stored, 'PX', ttl)
                if i == 1 then
                  govLimit = p.queueCap
                  govRemaining = math.max(0, math.floor((p.queueCap * 1000 - level) / 1000))
                end
              elseif p.algo == 6 then
                redis.call('SADD', KEYS[i], p.member)
                redis.call('PEXPIRE', KEYS[i], p.leaseMs)
                if i == 1 then
                  local held = redis.call('SCARD', KEYS[i])
                  govLimit, govRemaining = p.maxConc, math.max(0, p.maxConc - held)
                end
              end
            end
            return {1, govLimit, govRemaining}
            """, List.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttlGrace;

    public RedisRateLimitStore(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.keyPrefix = properties.getKeyPrefix();
        this.ttlGrace = properties.getTtlGrace();
    }

    @Override
    public RateLimitDecision consume(Policy policy, String identityType, String identity, long nowMillis) {
        long windowMillis = policy.window().toMillis();
        long elapsed = Math.floorMod(nowMillis, windowMillis);
        long ttlMillis = (windowMillis - elapsed) + ttlGrace.toMillis();
        String key = key(policy, identityType, identity, nowMillis);

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(CONSUME, List.of(key), String.valueOf(ttlMillis));
            if (result == null || result.size() < 2 || result.get(0) == null || result.get(1) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no decision for " + key, null);
            }
            long count = result.get(0);
            if (count <= policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), (int) Math.max(0, policy.limit() - count));
            }
            // Retry-After comes from the live TTL, so it always covers the rest of the real window.
            long retryAfter = Math.max(1, Math.ceilDiv(result.get(1), 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public Duration controlPlaneLockRemaining(String ip) {
        try {
            Long ttl = redis.getExpire(keyPrefix + ":cp:lock:" + hash(ip), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ttl == null) {
                throw new RateLimitStoreUnavailableException("redis returned no lock ttl", null);
            }
            return ttl > 0 ? Duration.ofMillis(ttl) : Duration.ZERO;
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public void controlPlaneRecordFailure(String ip, int maxFailures, Duration countWindow, Duration lockout) {
        try {
            String id = hash(ip);
            redis.execute(CP_FAILURE, List.of(keyPrefix + ":cp:fail:" + id, keyPrefix + ":cp:lock:" + id),
                    String.valueOf(maxFailures), String.valueOf(countWindow.toMillis()),
                    String.valueOf(Math.max(1, lockout.toSeconds())));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public RateLimitDecision peek(Policy policy, String identityType, String identity, long nowMillis) {
        long windowMillis = policy.window().toMillis();
        long elapsed = Math.floorMod(nowMillis, windowMillis);
        long expectedTtlMillis = (windowMillis - elapsed) + ttlGrace.toMillis();
        String key = key(policy, identityType, identity, nowMillis);

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(PEEK, List.of(key));
            if (result == null || result.size() < 2 || result.get(0) == null || result.get(1) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no peek for " + key, null);
            }
            long count = result.get(0);
            long ttl = result.get(1);
            if (ttl == -2) {
                return RateLimitDecision.allow(policy.limit(), policy.limit());
            }
            if (count < policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), (int) Math.max(0, policy.limit() - count));
            }
            long effectiveTtl = ttl >= 0 ? ttl : expectedTtlMillis;
            long retryAfter = Math.max(1, Math.ceilDiv(effectiveTtl, 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public BatchDecision consumeAll(java.util.List<Charge> charges, long nowMillis) {
        if (charges.isEmpty()) {
            throw new IllegalArgumentException("consumeAll requires at least one charge");
        }
        // One member per batch for sorted-set and lease entries. UUID plus the index keeps concurrent
        // batches from overwriting each other's members.
        String batch = java.util.UUID.randomUUID().toString();
        var keys = new java.util.ArrayList<String>(charges.size());
        var args = new java.util.ArrayList<String>(charges.size());
        var leases = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < charges.size(); i++) {
            Charge charge = charges.get(i);
            var policy = charge.policy();
            keys.add(stateKey(policy, charge.identityType(), charge.identity(), nowMillis));
            String member = batch + ":" + i;
            args.add(paramsJson(policy, member));
            if (policy.algorithm() == com.example.ratelimit.policy.Algorithm.CONCURRENCY_LIMIT) {
                leases.put(policy.id(), member);
            }
        }

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(BATCH, keys, (Object[]) args.toArray(new String[0]));
            // Denial layout {0, index, retrySecs, limit} has 4 elements; success {1, govLimit,
            // govRemaining} has 3. Both carry everything the decision needs.
            if (result == null || result.size() < 3 || result.get(0) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no batch decision", null);
            }
            if (result.get(0) == 0) {
                int blockedIndex = result.get(1).intValue() - 1;
                return new BatchDecision(blockedIndex, RateLimitDecision.reject(
                        result.get(3).intValue(), Duration.ofSeconds(Math.max(1, result.get(2)))),
                        java.util.Map.of());
            }
            return new BatchDecision(-1, RateLimitDecision.allow(result.get(1).intValue(),
                    result.get(2).intValue()), java.util.Map.copyOf(leases));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public void releaseConcurrency(com.example.ratelimit.policy.PolicyDocument policy, String identityType,
            String identity, String leaseId) {
        if (policy.algorithm() != com.example.ratelimit.policy.Algorithm.CONCURRENCY_LIMIT) {
            return;
        }
        String key = stateKey(policy, identityType, identity, System.currentTimeMillis());
        try {
            redis.opsForSet().remove(key, leaseId);
            Long remaining = redis.opsForSet().size(key);
            if (remaining != null && remaining == 0) {
                redis.delete(key);
            }
        } catch (DataAccessException e) {
            // The request already completed; a lost release is bounded by the lease TTL.
            org.slf4j.LoggerFactory.getLogger(RedisRateLimitStore.class)
                    .warn("concurrency release failed for policy {}: {}", policy.id(), e.getMessage());
        }
    }

    /**
     * State key per algorithm. All algorithms live under the {@code rate-limit:v1} namespace with
     * an algorithm-specific segment ({@code sw}, {@code sc}, {@code tb}, {@code lb}, {@code cc})
     * so counters, buckets, queues and leases are visibly separate while sharing the same prefix.
     */
    String stateKey(com.example.ratelimit.policy.PolicyDocument policy, String identityType,
            String identity, long nowMillis) {
        String subject = identityType.toLowerCase() + ":" + hash(identity);
        return switch (policy.algorithm()) {
            case FIXED_WINDOW -> key(fixedProjection(policy), identityType, identity, nowMillis);
            case SLIDING_WINDOW -> "%s:sw:%s:%s".formatted(keyPrefix, policy.id(), subject);
            case SLIDING_WINDOW_COUNTER -> "%s:sc:%s:%s".formatted(keyPrefix, policy.id(), subject);
            case TOKEN_BUCKET -> "%s:tb:%s:%s".formatted(keyPrefix, policy.id(), subject);
            case LEAKY_BUCKET -> "%s:lb:%s:%s".formatted(keyPrefix, policy.id(), subject);
            case CONCURRENCY_LIMIT -> "%s:cc:%s:%s".formatted(keyPrefix, policy.id(), subject);
        };
    }

    /** Numbers-only JSON the batch script decodes with cjson. */
    private String paramsJson(com.example.ratelimit.policy.PolicyDocument policy, String member) {
        var algo = policy.algorithm();
        // Leaky policing has no fixed window; windowMs carries its full-capacity drain horizon for
        // malformed/legacy state recovery. Normal state expires at its current water-level drain time.
        long windowMs = policy.window() == null ? 0 : policy.window().toMillis();
        int cost = policy.cost() == null ? 1 : policy.cost();
        if (algo == com.example.ratelimit.policy.Algorithm.LEAKY_BUCKET) {
            windowMs = policy.drainRate() == null || policy.drainRate() < 1 || policy.queueCapacity() == null
                    ? 0
                    : (long) Math.ceil((double) policy.queueCapacity() / policy.drainRate() * 1000);
        }
        return "{\"algo\":" + algoId(algo)
                + ",\"limit\":" + (policy.limit() == null ? 0 : policy.limit())
                + ",\"windowMs\":" + windowMs
                + ",\"graceMs\":" + ttlGrace.toMillis()
                + ",\"capacity\":" + (policy.capacity() == null ? 0 : policy.capacity())
                + ",\"refillMs\":" + (policy.refillInterval() == null ? 0 : policy.refillInterval().toMillis())
                + ",\"cost\":" + cost
                + ",\"drainRate\":" + (policy.drainRate() == null ? 0 : policy.drainRate())
                + ",\"queueCap\":" + (policy.queueCapacity() == null ? 0 : policy.queueCapacity())
                + ",\"maxConc\":" + (policy.maxConcurrent() == null ? 0 : policy.maxConcurrent())
                + ",\"leaseMs\":" + (policy.leaseDuration() == null ? 0 : policy.leaseDuration().toMillis())
                + ",\"member\":\"" + member + "\"}";
    }

    private static int algoId(com.example.ratelimit.policy.Algorithm algo) {
        return switch (algo) {
            case FIXED_WINDOW -> 1;
            case SLIDING_WINDOW -> 2;
            case SLIDING_WINDOW_COUNTER -> 3;
            case TOKEN_BUCKET -> 4;
            case LEAKY_BUCKET -> 5;
            case CONCURRENCY_LIMIT -> 6;
        };
    }

    private static RateLimitProperties.Policy fixedProjection(
            com.example.ratelimit.policy.PolicyDocument policy) {
        RateLimitProperties.Identity identity =
                policy.scope() == com.example.ratelimit.policy.Scope.USER
                        ? RateLimitProperties.Identity.USER
                        : RateLimitProperties.Identity.IP;
        return new RateLimitProperties.Policy(policy.id(), policy.method(), policy.path(),
                policy.limit() == null ? 0 : policy.limit(),
                policy.window() == null ? Duration.ofSeconds(1) : policy.window(), identity,
                policy.onRedisError());
    }

    /** sha-256 hex, first 16 chars: identifiers stay private and keys stay fixed width. */
    static String hash(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The one place the key layout is defined. The window id keeps windows from bleeding together. */
    public String keyFor(Policy policy, String identityType, String identity, long nowMillis) {
        return key(policy, identityType, identity, nowMillis);
    }

    private String key(Policy policy, String identityType, String identity, long nowMillis) {
        return "%s:%s:%s:%s:%d".formatted(keyPrefix, policy.id(), identityType.toLowerCase(),
                hash(identity), Math.floorDiv(nowMillis, policy.window().toMillis()));
    }
}
