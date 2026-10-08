package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.ratelimit.RedisTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Store-level guarantees for the managed policy store, against a real Redis.
 *
 * <p>Covers the properties the admin API depends on: namespacing that keeps policy edits separate from
 * counters, atomic compare-and-set versioning, seed-once behaviour, and audit entries that record
 * field names rather than values.
 */
class ManagedPolicyStoreTest {

    private static LettuceConnectionFactory factory;
    private ManagedPolicyStore store;
    private StringRedisTemplate redis;
    private final ProjectionService projections = new ProjectionService();

    @BeforeEach
    void setUp() {
        if (factory == null) {
            var container = RedisTestSupport.redis();
            var config = new RedisStandaloneConfiguration(container.getHost(),
                    container.getMappedPort(RedisTestSupport.REDIS_PORT));
            factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
        }
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        // Mirrors the mapper Spring Boot supplies: PolicyDocument carries Duration, which needs the
        // JavaTimeModule. A bare ObjectMapper would fail to serialise every policy.
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
        store = new ManagedPolicyStore(redis, mapper);
        // Each test starts from an empty store; isolation matters more than seeding realism here.
        store.reset("test-setup");
    }

    @AfterEach
    void tearDown() {
        // The Testcontainers Redis is shared across test classes, and policies carry no TTL. Without
        // this, a document written here would still be in place when another class boots and would be
        // picked up as a live policy by the enforcement path.
        store.reset("test-teardown");
    }

    private static PolicyDocument policy(String id, int limit) {
        Instant now = Instant.now();
        return PolicyDocument.builder(id)
                .name(id)
                .route("GET", "/api/" + id)
                .algorithm(Algorithm.FIXED_WINDOW)
                .scope(Scope.IP)
                .window(Duration.ofMinutes(1), limit)
                .enabled(true)
                .version(1)
                .timestamps(now, now)
                .updatedBy("test")
                .build();
    }

    @Test
    void savedPolicyRoundTripsThroughRedis() {
        var saved = store.save(policy("unit-products", 100), null, "alice-admin");
        assertThat(store.find("unit-products")).isPresent().get()
                .satisfies(found -> {
                    assertThat(found.id()).isEqualTo("unit-products");
                    assertThat(found.limit()).isEqualTo(100);
                    assertThat(found.algorithm()).isEqualTo(Algorithm.FIXED_WINDOW);
                    assertThat(found.version()).isEqualTo(1);
                });
        assertThat(saved.version()).isEqualTo(1);
    }

    @Test
    void policyKeysAreNamespacedAwayFromRateCounters() {
        store.save(policy("unit-products", 100), null, "admin");
        // A counter key from the enforcement path must never be mistaken for a policy document.
        var policyKeys = redis.keys(ManagedPolicyStore.NS + ":*");
        assertThat(policyKeys).isNotEmpty();
        assertThat(policyKeys).allSatisfy(k -> assertThat(k).startsWith(ManagedPolicyStore.NS));

        redis.opsForValue().set("rate-limit:v1:products-read:ip:abc:1", "5");
        assertThat(store.find("unit-products")).isPresent();
        assertThat(redis.keys(ManagedPolicyStore.NS + ":*"))
                .noneSatisfy(k -> assertThat(k).contains("rate-limit:v1"));
    }

    @Test
    void createRefusesToClobberAnExistingId() {
        store.save(policy("unit-products", 100), null, "admin");
        assertThatThrownBy(() -> store.save(policy("unit-products", 999), null, "admin"))
                .isInstanceOf(ManagedPolicyStore.PolicyNotFoundException.class);
        // The original is untouched.
        assertThat(store.find("unit-products").orElseThrow().limit()).isEqualTo(100);
    }

    @Test
    void updateWithStaleVersionIsRejectedAndLeavesPolicyIntact() {
        var stored = store.save(policy("unit-products", 100), null, "admin");

        // Simulate a second admin reading version 1 and saving version 3: a lost update.
        var stale = PolicyDocument.builder(stored.id())
                .name(stored.name()).route(stored.method(), stored.path())
                .algorithm(stored.algorithm()).scope(stored.scope())
                .window(Duration.ofMinutes(1), 5)
                .version(3).timestamps(stored.createdAt(), Instant.now()).updatedBy("other-admin").build();

        assertThatThrownBy(() -> store.save(stale, stored, "other-admin"))
                .isInstanceOf(PolicyConflictException.class);
        assertThat(store.find("unit-products").orElseThrow().limit())
                .as("a rejected save must not partially replace the policy")
                .isEqualTo(100);
    }

    @Test
    void updateWithCorrectVersionAdvancesTheVersion() {
        var stored = store.save(policy("unit-products", 100), null, "admin");
        var next = PolicyDocument.builder(stored.id())
                .name(stored.name()).route(stored.method(), stored.path())
                .algorithm(stored.algorithm()).scope(stored.scope())
                .window(Duration.ofMinutes(1), 250)
                .version(2).timestamps(stored.createdAt(), Instant.now()).updatedBy("admin").build();

        var saved = store.save(next, stored, "admin");
        assertThat(saved.version()).isEqualTo(2);
        assertThat(store.find("unit-products").orElseThrow().limit()).isEqualTo(250);
    }

    @Test
    void concurrentSavesLetExactlyOneWinnerThrough() throws Exception {
        var stored = store.save(policy("unit-products", 100), null, "admin");
        int threads = 8;
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);

        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            tasks.add(() -> {
                var candidate = PolicyDocument.builder(stored.id())
                        .name(stored.name()).route(stored.method(), stored.path())
                        .algorithm(stored.algorithm()).scope(stored.scope())
                        .window(Duration.ofMinutes(1), 100 + n)
                        .version(2).timestamps(stored.createdAt(), Instant.now())
                        .updatedBy("admin-" + n).build();
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    store.save(candidate, stored, "admin-" + n);
                    successes.incrementAndGet();
                } catch (PolicyConflictException e) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
        }
        try {
            for (var f : pool.invokeAll(tasks)) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).as("exactly one writer may win a version").isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(store.find("unit-products").orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void deleteRemovesTheDocumentAndTheIndexEntry() {
        store.save(policy("unit-products", 100), null, "admin");
        store.delete("unit-products", "admin", 1);
        assertThat(store.find("unit-products")).isEmpty();
        assertThat(store.findAll()).isEmpty();
        assertThatThrownBy(() -> store.delete("unit-products", "admin", 1))
                .isInstanceOf(ManagedPolicyStore.PolicyNotFoundException.class);
    }

    @Test
    void auditRecordsActorAndChangedFieldNames() {
        var stored = store.save(policy("unit-products", 100), null, "alice");
        var updated = PolicyDocument.builder(stored.id())
                .name(stored.name()).route(stored.method(), stored.path())
                .algorithm(stored.algorithm()).scope(stored.scope())
                .window(Duration.ofMinutes(1), 42)
                .version(2).timestamps(stored.createdAt(), Instant.now()).updatedBy("alice").build();
        store.save(updated, stored, "alice");

        var audit = store.audit(10);
        assertThat(audit).hasSize(2);
        // Newest first.
        assertThat(audit.get(0).operation()).isEqualTo("UPDATE");
        assertThat(audit.get(0).actor()).isEqualTo("alice");
        assertThat(audit.get(0).changedFields()).containsExactly("limit");
        assertThat(audit.get(1).operation()).isEqualTo("CREATE");
    }

    @Test
    void seedingIsIdempotentAndNeverOverwritesAdminEdits() {
        assertThat(store.isSeeded()).isFalse();
        store.markSeeded();
        assertThat(store.isSeeded()).isTrue();

        // An admin edit, then a simulated restart: the seeder must leave this alone.
        store.save(policy("unit-products", 7), null, "admin");
        assertThat(store.isSeeded()).as("seeded marker survives, so no reseed").isTrue();
        assertThat(store.find("unit-products").orElseThrow().limit()).isEqualTo(7);
    }

    @Test
    void resetClearsTheSeededMarkerSoReseedIsExplicit() {
        store.save(policy("unit-products", 100), null, "admin");
        store.markSeeded();
        store.reset("admin");
        assertThat(store.findAll()).isEmpty();
        assertThat(store.isSeeded()).as("reset must allow an intentional reseed").isFalse();
    }

    @Test
    void resetRemovesGroupsAndProjectionsAndAdvancesEpoch() {
        var now = Instant.now();
        var rule = new ScopeRule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1),
                10, null, null, null, null, null, null, null, null);
        var endpoint = new EndpointRule("ep-reset", "GET", "/api/reset", "Reset", true, false,
                List.of(rule));
        var group = new PolicyGroup("grp-reset", "Reset", true, List.of(endpoint),
                FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var docs = projections.projectGroup(group);
        store.saveGroup(group, null, "test", docs, store.getEpoch());
        long before = store.getEpoch();

        store.reset("test");

        assertThat(store.getEpoch()).isGreaterThan(before);
        assertThat(store.findGroup(group.id())).isEmpty();
        assertThat(store.findAll()).isEmpty();
        assertThat(redis.opsForSet().members(ManagedPolicyStore.PROJECTIONS)).isEmpty();
        assertThatThrownBy(() -> store.saveGroup(group, null, "test", docs, before))
                .isInstanceOf(PolicyConflictException.class);
        assertThat(store.findGroup(group.id())).isEmpty();
    }

    @Test
    void deletingGroupRemovesPersistedProjectionIdsAndIndexEntries() {
        var now = Instant.now();
        var rule = new ScopeRule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1),
                10, null, null, null, null, null, null, null, null);
        var endpoint = new EndpointRule("ep-delete", "GET", "/api/delete", "Delete", true, false,
                List.of(rule));
        var group = new PolicyGroup("grp-delete", "Delete", true, List.of(endpoint),
                FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var saved = store.saveGroup(group, null, "test", projections.projectGroup(group), store.getEpoch());
        assertThat(saved.projectionIds()).hasSize(1);

        store.deleteGroup(group.id(), "test", store.getEpoch());

        assertThat(store.findAll()).isEmpty();
        assertThat(redis.opsForSet().members(ManagedPolicyStore.PROJECTIONS)).isEmpty();
        assertThat(redis.opsForSet().members(ManagedPolicyStore.INDEX)).isEmpty();
    }

    @Test
    void repairRemovesStaleRecordedGroupProjectionAndReturnsCounts() throws Exception {
        var now = Instant.now();
        var rule = new ScopeRule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1),
                10, null, null, null, null, null, null, null, null);
        var endpoint = new EndpointRule("ep-repair", "GET", "/api/repair", "Repair", true, false,
                List.of(rule));
        var group = new PolicyGroup("grp-repair", "Repair", true, List.of(endpoint),
                FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var projection = projections.projectGroup(group).get(0);
        store.saveGroup(group, null, "test", List.of(projection), store.getEpoch());
        String staleId = "p-" + "a".repeat(56);
        redis.opsForHash().put(ManagedPolicyStore.NS + ":group:" + group.id(), "projectionIds",
                new ObjectMapper().writeValueAsString(List.of(projection.id(), staleId)));
        redis.opsForHash().put(ManagedPolicyStore.NS + ":doc:" + staleId, "doc", "{}");
        redis.opsForSet().add(ManagedPolicyStore.PROJECTIONS, staleId);
        redis.opsForSet().add(ManagedPolicyStore.INDEX, staleId);

        var counts = store.repairProjections(group.id(), "test");

        assertThat(counts.deleted()).isEqualTo(1);
        assertThat(counts.written()).isZero();
        assertThat(redis.opsForSet().isMember(ManagedPolicyStore.PROJECTIONS, staleId)).isFalse();
        assertThat(redis.opsForSet().isMember(ManagedPolicyStore.INDEX, staleId)).isFalse();
    }

    @Test
    void invalidPolicyIsRejectedBeforeAnythingIsWritten() {
        var now = Instant.now();
        var noLimit = PolicyDocument.builder("broken")
                .route("GET", "/api/broken")
                .algorithm(Algorithm.FIXED_WINDOW).scope(Scope.IP)
                .window(Duration.ofMinutes(1), null)
                .version(1).timestamps(now, now).build();
        assertThatThrownBy(() -> store.save(noLimit, null, "admin"))
                .isInstanceOf(PolicyValidationException.class);
        assertThat(store.find("broken")).isEmpty();
    }

    @Test
    void implementedAlgorithmsAreStorable() {
        var now = Instant.now();
        var tokenBucket = PolicyDocument.builder("tb")
                .route("GET", "/api/tb")
                .algorithm(Algorithm.TOKEN_BUCKET).scope(Scope.IP)
                .bucket(100, Duration.ofSeconds(10), 1)
                .version(1).timestamps(now, now).build();
        var saved = store.save(tokenBucket, null, "admin");
        assertThat(saved.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
        assertThat(store.find("tb")).isPresent();
    }
}
