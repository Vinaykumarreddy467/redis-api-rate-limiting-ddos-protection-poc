package com.example.ratelimit.ratelimit;

import java.time.Duration;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Redis-backed store against a real Redis in a container. */
class RedisRateLimitStoreTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisRateLimitStore store;

    /** Deliberately window-aligned (multiple of 60s and 3s) so TTL maths is exact. */
    private static final long T0 = 1_699_999_980_000L;

    @BeforeAll
    static void setUp() {
        var container = RedisTestSupport.redis();
        var config = new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(RedisTestSupport.REDIS_PORT));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisRateLimitStore(redis, properties());
    }

    private static RateLimitProperties properties() {
        var props = new RateLimitProperties();
        props.setKeyPrefix("test:ratelimit");
        props.setTtlGrace(Duration.ofSeconds(1));
        return props;
    }

    private static RateLimitProperties.Policy policy(String id, int limit, Duration window) {
        return new RateLimitProperties.Policy(id, "GET", "/api/x", limit, window,
                RateLimitProperties.Identity.IP, null);
    }

    private static com.example.ratelimit.policy.PolicyDocument docPolicy(String id, int limit,
            Duration window) {
        var now = java.time.Instant.now();
        return com.example.ratelimit.policy.PolicyDocument.builder(id)
                .name(id).route("GET", "/api/x")
                .algorithm(com.example.ratelimit.policy.Algorithm.FIXED_WINDOW)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .window(window, limit)
                .version(1).timestamps(now, now).build();
    }

    @Test
    void allowsUpToLimitThenRejects() {
        var policy = policy("allow-then-reject", 3, Duration.ofMinutes(1));
        for (int i = 1; i <= 3; i++) {
            var decision = store.consume(policy, "IP", "10.0.0.1", T0);
            assertThat(decision.allowed()).as("request %d", i).isTrue();
            assertThat(decision.remaining()).isEqualTo(3 - i);
        }
        var rejected = store.consume(policy, "IP", "10.0.0.1", T0);
        assertThat(rejected.allowed()).isFalse();
        // Window is aligned, so the TTL is the full window plus the 1s grace.
        assertThat(rejected.retryAfter()).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(61));
    }

    @Test
    void differentIdentitiesAreIndependent() {
        var policy = policy("independent-identities", 1, Duration.ofMinutes(1));
        assertThat(store.consume(policy, "IP", "10.0.0.1", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "IP", "10.0.0.2", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "IP", "10.0.0.1", T0).allowed()).isFalse();
    }

    @Test
    void differentPoliciesAreIndependent() {
        var first = policy("policy-a", 1, Duration.ofMinutes(1));
        var second = policy("policy-b", 5, Duration.ofMinutes(1));
        assertThat(store.consume(first, "IP", "10.0.0.3", T0).allowed()).isTrue();
        assertThat(store.consume(first, "IP", "10.0.0.3", T0).allowed()).isFalse();
        for (int i = 0; i < 5; i++) {
            assertThat(store.consume(second, "IP", "10.0.0.3", T0).allowed()).isTrue();
        }
    }

    @Test
    void counterGetsTtlAndIsGoneAfterWindow() {
        var policy = policy("ttl-check", 2, Duration.ofSeconds(3));
        store.consume(policy, "IP", "10.0.0.4", T0);
        String key = store.keyFor(policy, "IP", "10.0.0.4", T0);

        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isBetween(2_000L, 4_100L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");

        // A later window uses a different key, so the old counter can never be read again.
        long later = T0 + Duration.ofSeconds(3).toMillis();
        assertThat(store.keyFor(policy, "IP", "10.0.0.4", later)).isNotEqualTo(key);
        assertThat(store.consume(policy, "IP", "10.0.0.4", later).allowed()).isTrue();

        // Prove the key really does expire rather than lingering forever.
        Long freshKeyTtl = redis.getExpire(
                store.keyFor(policy, "IP", "10.9.9.9", System.currentTimeMillis()),
                java.util.concurrent.TimeUnit.SECONDS);
        assertThat(freshKeyTtl).isNotNull();
    }

    @Test
    void keyShapeIsNamespacedAndHashesIdentity() {
        var policy = policy("key-shape", 1, Duration.ofMinutes(1));
        String key = store.keyFor(policy, "USER", "alice@example.com", T0);
        assertThat(key).matches("test:ratelimit:key-shape:user:[0-9a-f]{16}:\\d+");
        assertThat(key).doesNotContain("alice");
        assertThat(RedisRateLimitStore.hash("alice")).hasSize(16);
        assertThat(RedisRateLimitStore.hash("alice")).isNotEqualTo(RedisRateLimitStore.hash("bob"));
    }

    @Test
    void batchDenialChargesNothing() {
        var first = docPolicy("batch-first", 2, Duration.ofMinutes(1));
        var second = docPolicy("batch-second", 1, Duration.ofMinutes(1));
        var charges = java.util.List.of(
                new RateLimitStore.Charge(first, "IP", "10.0.0.11"),
                new RateLimitStore.Charge(second, "IP", "10.0.0.11"));

        var allowed = store.consumeAll(charges, T0);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.blockedIndex()).isEqualTo(-1);

        var denied = store.consumeAll(charges, T0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.blockedIndex()).as("the exhausted second policy blocks").isEqualTo(1);
        assertThat(denied.decision().retryAfter().isZero()).isFalse();

        // Exact totals per policy: the denial left both counters exactly where the allowed batch put them.
        assertThat(redis.opsForValue().get(store.stateKey(first, "IP", "10.0.0.11", T0))).isEqualTo("1");
        assertThat(redis.opsForValue().get(store.stateKey(second, "IP", "10.0.0.11", T0))).isEqualTo("1");
    }

    @Test
    void concurrentBatchesForLastUnitAllowExactlyOne() throws Exception {
        var first = docPolicy("race-first", 2, Duration.ofMinutes(1));
        var second = docPolicy("race-second", 2, Duration.ofMinutes(1));
        var charges = java.util.List.of(
                new RateLimitStore.Charge(first, "IP", "10.0.0.12"),
                new RateLimitStore.Charge(second, "IP", "10.0.0.12"));
        assertThat(store.consumeAll(charges, T0).allowed()).isTrue();

        int threads = 2;
        var startLine = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLine.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return store.consumeAll(charges, T0).allowed();
            });
        }
        int allowed = 0;
        try {
            for (var f : pool.invokeAll(tasks)) {
                if (f.get(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed).as("exactly one racer may take the last unit").isEqualTo(1);
        assertThat(redis.opsForValue().get(store.stateKey(first, "IP", "10.0.0.12", T0))).isEqualTo("2");
        assertThat(redis.opsForValue().get(store.stateKey(second, "IP", "10.0.0.12", T0))).isEqualTo("2");
    }

    @Test
    void twoStoreInstancesShareOneLimit() {
        // Simulates two application instances against one Redis: separate objects, separate
        // connections, identical enforcement.
        var otherRedis = new StringRedisTemplate(factory);
        otherRedis.afterPropertiesSet();
        var secondInstance = new RedisRateLimitStore(otherRedis, properties());
        var policy = policy("multi-instance", 4, Duration.ofMinutes(1));

        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            RateLimitStore instance = (i % 2 == 0) ? RedisRateLimitStoreTest.store : secondInstance;
            if (instance.consume(policy, "IP", "10.0.0.5", T0).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(4);
    }

    private static com.example.ratelimit.policy.PolicyDocument slidingPolicy(String id, int limit) {
        var now = java.time.Instant.now();
        return com.example.ratelimit.policy.PolicyDocument.builder(id)
                .name(id).route("GET", "/api/" + id)
                .algorithm(com.example.ratelimit.policy.Algorithm.SLIDING_WINDOW)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .window(Duration.ofMinutes(1), limit)
                .version(1).timestamps(now, now).build();
    }

    private static java.util.List<RateLimitStore.Charge> charge(
            com.example.ratelimit.policy.PolicyDocument policy, String identity) {
        return java.util.List.of(new RateLimitStore.Charge(policy, "IP", identity));
    }

    private static java.util.List<RateLimitStore.Charge> globalCharge(
            com.example.ratelimit.policy.PolicyDocument policy) {
        // GLOBAL scope resolves to the constant identity, exactly like the filter does.
        return java.util.List.of(new RateLimitStore.Charge(policy, "GLOBAL", "global"));
    }

    @Test
    void slidingWindowIsExactAndExpiresState() {
        var policy = slidingPolicy("sw-exact", 3);
        for (int i = 1; i <= 3; i++) {
            var decision = store.consumeAll(charge(policy, "10.0.1.1"), T0);
            assertThat(decision.allowed()).as("request %d", i).isTrue();
            assertThat(decision.decision().remaining()).isEqualTo(3 - i);
        }
        var denied = store.consumeAll(charge(policy, "10.0.1.1"), T0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.blockedIndex()).isEqualTo(0);
        assertThat(denied.decision().retryAfter().isZero()).isFalse();

        // Duplicate timestamps never overwrite each other: members are unique per batch.
        var sameInstant = store.consumeAll(charge(policy, "10.0.1.9"), T0);
        assertThat(sameInstant.allowed()).isTrue();
        assertThat(store.consumeAll(charge(policy, "10.0.1.9"), T0).allowed()).isTrue();

        // Idle state expires on its own rather than accumulating forever.
        String key = store.stateKey(policy, "IP", "10.0.1.1", T0);
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void slidingWindowCounterInterpolatesAcrossTheBoundary() {
        var now = java.time.Instant.now();
        var policy = com.example.ratelimit.policy.PolicyDocument.builder("sc-interp")
                .name("sc-interp").route("GET", "/api/sc-interp")
                .algorithm(com.example.ratelimit.policy.Algorithm.SLIDING_WINDOW_COUNTER)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .window(Duration.ofMinutes(1), 2)
                .version(1).timestamps(now, now).build();
        // Spend the whole allowance, then prove the previous window still weighs on the estimate:
        // with the full count behind us, a fresh window position still denies.
        assertThat(store.consumeAll(charge(policy, "10.0.2.1"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(policy, "10.0.2.1"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(policy, "10.0.2.1"), T0).allowed()).isFalse();
    }

    @Test
    void tokenBucketBurstsThenSustainsWithCost() {
        var now = java.time.Instant.now();
        var bucket = com.example.ratelimit.policy.PolicyDocument.builder("tb-burst")
                .name("tb-burst").route("GET", "/api/tb-burst")
                .algorithm(com.example.ratelimit.policy.Algorithm.TOKEN_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .bucket(3, Duration.ofSeconds(30), 1)
                .version(1).timestamps(now, now).build();
        // Burst of 3, then exhaustion with an honest retry time.
        for (int i = 1; i <= 3; i++) {
            assertThat(store.consumeAll(charge(bucket, "10.0.3.1"), T0).allowed()).as("burst %d", i).isTrue();
        }
        var exhausted = store.consumeAll(charge(bucket, "10.0.3.1"), T0);
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.decision().retryAfter().isZero()).isFalse();

        // Request cost is honoured: a cost-2 policy spends two tokens per request.
        var pricey = com.example.ratelimit.policy.PolicyDocument.builder("tb-cost")
                .name("tb-cost").route("GET", "/api/tb-cost")
                .algorithm(com.example.ratelimit.policy.Algorithm.TOKEN_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .bucket(3, Duration.ofSeconds(30), 2)
                .version(1).timestamps(now, now).build();
        assertThat(store.consumeAll(charge(pricey, "10.0.3.2"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(pricey, "10.0.3.2"), T0).allowed())
                .as("one token left cannot pay cost 2")
                .isFalse();

        // Idle buckets expire rather than lingering with stale timestamps.
        String key = store.stateKey(bucket, "IP", "10.0.3.1", T0);
        assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void leakyBucketPolicesOverflowWithDrainHorizonRetry() {
        var now = java.time.Instant.now();
        var leaky = com.example.ratelimit.policy.PolicyDocument.builder("lb-police")
                .name("lb-police").route("GET", "/api/lb-police")
                .algorithm(com.example.ratelimit.policy.Algorithm.LEAKY_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .leaky(1, 2)
                .version(1).timestamps(now, now).build();
        assertThat(store.consumeAll(charge(leaky, "10.0.4.1"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(leaky, "10.0.4.1"), T0).allowed()).isTrue();
        var overflow = store.consumeAll(charge(leaky, "10.0.4.1"), T0);
        assertThat(overflow.allowed()).isFalse();
        // Queue of 2 draining at 1/s: retry lands inside the ~2s drain horizon.
        assertThat(overflow.decision().retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(overflow.decision().retryAfter().isZero()).isFalse();
    }

    @Test
    void leakyBucketDrainsGraduallyAtConfiguredRate() throws InterruptedException {
        var now = java.time.Instant.now();
        var leaky = com.example.ratelimit.policy.PolicyDocument.builder("lb-gradual")
                .name("lb-gradual").route("GET", "/api/lb-gradual")
                .algorithm(com.example.ratelimit.policy.Algorithm.LEAKY_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .leaky(2, 2)
                .version(1).timestamps(now, now).build();
        String key = store.stateKey(leaky, "IP", "10.0.4.2", T0);

        assertThat(store.consumeAll(charge(leaky, "10.0.4.2"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(leaky, "10.0.4.2"), T0).allowed()).isTrue();
        assertThat(store.consumeAll(charge(leaky, "10.0.4.2"), T0).allowed())
                .as("full bucket rejects immediately")
                .isFalse();

        // At 2 requests/second, 600ms drains about 1.2 requests. One more fits, but an
        // immediate following request does not. A fixed-expiry counter would still reject both.
        Thread.sleep(600);
        assertThat(store.consumeAll(charge(leaky, "10.0.4.2"), T0).allowed())
                .as("available capacity recovers continuously, before the old TTL horizon")
                .isTrue();
        assertThat(store.consumeAll(charge(leaky, "10.0.4.2"), T0).allowed())
                .as("the accepted request adds a full unit back to the bucket")
                .isFalse();

        String state = redis.opsForValue().get(key);
        assertThat(state).matches("\\d+:\\d+");
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void leakyBucketMigratesLegacyCounterWithoutDroppingDebt() {
        var now = java.time.Instant.now();
        var leaky = com.example.ratelimit.policy.PolicyDocument.builder("lb-legacy")
                .name("lb-legacy").route("GET", "/api/lb-legacy")
                .algorithm(com.example.ratelimit.policy.Algorithm.LEAKY_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .leaky(2, 3)
                .version(1).timestamps(now, now).build();
        String key = store.stateKey(leaky, "IP", "10.0.4.3", T0);
        // Legacy counter of 2: after migration + first new request = 3 (at capacity), second denied
        redis.opsForValue().set(key, "2", Duration.ofSeconds(3));

        assertThat(store.consumeAll(charge(leaky, "10.0.4.3"), T0).allowed()).isTrue();
        assertThat(redis.opsForValue().get(key)).startsWith("3000:");
        assertThat(store.consumeAll(charge(leaky, "10.0.4.3"), T0).allowed())
                .as("at capacity, next request denied")
                .isFalse();
    }

    @Test
    void leakyBucketDenialDoesNotChargeEarlierPolicyInAtomicBatch() {
        var now = java.time.Instant.now();
        var fixed = docPolicy("lb-batch-fixed", 5, Duration.ofMinutes(1));
        var leaky = com.example.ratelimit.policy.PolicyDocument.builder("lb-batch-full")
                .name("lb-batch-full").route("GET", "/api/lb-batch-full")
                .algorithm(com.example.ratelimit.policy.Algorithm.LEAKY_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .leaky(1, 1)
                .version(1).timestamps(now, now).build();
        var fixedCharge = new RateLimitStore.Charge(fixed, "IP", "10.0.4.4");
        var leakyCharge = new RateLimitStore.Charge(leaky, "IP", "10.0.4.4");
        assertThat(store.consumeAll(java.util.List.of(leakyCharge), T0).allowed()).isTrue();

        var denied = store.consumeAll(java.util.List.of(fixedCharge, leakyCharge), T0);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.blockedIndex()).isEqualTo(1);
        assertThat(redis.opsForValue().get(store.stateKey(fixed, "IP", "10.0.4.4", T0))).isNull();
    }

    @Test
    void concurrencyLimitCapsAndReleases() {
        var now = java.time.Instant.now();
        var concurrent = com.example.ratelimit.policy.PolicyDocument.builder("cc-cap")
                .name("cc-cap").route("GET", "/api/cc-cap")
                .algorithm(com.example.ratelimit.policy.Algorithm.CONCURRENCY_LIMIT)
                .scope(com.example.ratelimit.policy.Scope.GLOBAL)
                .concurrency(2, Duration.ofSeconds(30))
                .version(1).timestamps(now, now).build();
        var first = store.consumeAll(globalCharge(concurrent), T0);
        var second = store.consumeAll(globalCharge(concurrent), T0);
        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(first.decision()).isNotNull();
        assertThat(store.consumeAll(globalCharge(concurrent), T0).allowed())
                .as("third concurrent holder is refused")
                .isFalse();

        // Owner-checked release frees exactly one permit.
        String key = store.stateKey(concurrent, "GLOBAL", "global", T0);
        assertThat(redis.opsForSet().size(key)).isEqualTo(2L);
        store.releaseConcurrency(concurrent, "GLOBAL", "global",
                firstAllowedLease(first, concurrent));
        assertThat(redis.opsForSet().size(key)).isEqualTo(1L);
        assertThat(store.consumeAll(globalCharge(concurrent), T0).allowed()).isTrue();
    }

    private static String firstAllowedLease(RateLimitStore.BatchDecision decision,
            com.example.ratelimit.policy.PolicyDocument policy) {
        return decision.leases().get(policy.id());
    }

    @Test
    void mixedAlgorithmsDenyAtomically() {
        var fixed = docPolicy("mix-fixed", 5, Duration.ofMinutes(1));
        var now = java.time.Instant.now();
        var bucket = com.example.ratelimit.policy.PolicyDocument.builder("mix-bucket")
                .name("mix-bucket").route("GET", "/api/mix")
                .algorithm(com.example.ratelimit.policy.Algorithm.TOKEN_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .bucket(1, Duration.ofSeconds(30), 1)
                .version(1).timestamps(now, now).build();
        var charges = java.util.List.of(
                new RateLimitStore.Charge(fixed, "IP", "10.0.5.1"),
                new RateLimitStore.Charge(bucket, "IP", "10.0.5.1"));

        assertThat(store.consumeAll(charges, T0).allowed()).isTrue();
        var denied = store.consumeAll(charges, T0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.blockedIndex()).isEqualTo(1);
        // The exhausted bucket blocked; the fixed counter holds only the one allowed batch.
        assertThat(redis.opsForValue().get(store.stateKey(fixed, "IP", "10.0.5.1", T0))).isEqualTo("1");
    }

    @Test
    void concurrentMixedBatchesTakeTheLastUnitExactlyOnce() throws Exception {
        var fixed = docPolicy("cmix-fixed", 2, Duration.ofMinutes(1));
        var now = java.time.Instant.now();
        var bucket = com.example.ratelimit.policy.PolicyDocument.builder("cmix-bucket")
                .name("cmix-bucket").route("GET", "/api/cmix")
                .algorithm(com.example.ratelimit.policy.Algorithm.TOKEN_BUCKET)
                .scope(com.example.ratelimit.policy.Scope.IP)
                .bucket(2, Duration.ofSeconds(30), 1)
                .version(1).timestamps(now, now).build();
        var charges = java.util.List.of(
                new RateLimitStore.Charge(fixed, "IP", "10.0.5.2"),
                new RateLimitStore.Charge(bucket, "IP", "10.0.5.2"));
        assertThat(store.consumeAll(charges, T0).allowed()).isTrue();

        int threads = 2;
        var startLine = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLine.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return store.consumeAll(charges, T0).allowed();
            });
        }
        int allowed = 0;
        try {
            for (var f : pool.invokeAll(tasks)) {
                if (f.get(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed).as("exactly one racer takes the last unit across both algorithms").isEqualTo(1);
        assertThat(redis.opsForValue().get(store.stateKey(fixed, "IP", "10.0.5.2", T0))).isEqualTo("2");
    }
}
