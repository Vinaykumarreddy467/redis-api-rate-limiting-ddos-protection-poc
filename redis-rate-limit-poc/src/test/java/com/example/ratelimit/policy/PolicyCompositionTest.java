package com.example.ratelimit.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.ratelimit.RateLimitDecision;
import com.example.ratelimit.ratelimit.RateLimitFilter;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import com.example.ratelimit.ratelimit.RateLimitMetrics;
import com.example.ratelimit.ratelimit.RateLimitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting policy semantics through the real filter with an in-memory store double.
 *
 * <p>Proves the wiring the Redis tests cannot: that a GLOBAL policy charges one shared quota no
 * matter which client arrives, and that one USER policy on a wide route pattern spans endpoints.
 * The atomicity itself is proven against real Redis in {@code RedisRateLimitStoreTest}; this class
 * proves scope resolution feeds the batch the right identities.
 */
class PolicyCompositionTest {

    /** Fixed-window stand-in keyed exactly like the Redis layout: policy, scope type, identity. */
    private static final class TrackingStore implements RateLimitStore {
        final Map<String, Integer> counts = new HashMap<>();
        int consumes;

        private static String key(RateLimitProperties.Policy policy, String type, String identity) {
            return policy.id() + "|" + type + "|" + identity;
        }

        @Override
        public RateLimitDecision peek(RateLimitProperties.Policy policy, String type, String identity,
                long now) {
            int count = counts.getOrDefault(key(policy, type, identity), 0);
            if (count < policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(60));
        }

        @Override
        public RateLimitDecision consume(RateLimitProperties.Policy policy, String type, String identity,
                long now) {
            consumes++;
            int count = counts.merge(key(policy, type, identity), 1, Integer::sum);
            if (count <= policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(60));
        }
    }

    private final TrackingStore store = new TrackingStore();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static ExemptionStore noExemptions() {
        return new ExemptionStore(null, new ObjectMapper()) {
            @Override
            public java.util.List<ExemptionDocument> findAll() {
                return java.util.List.of();
            }
        };
    }

    private RateLimitFilter filter(List<PolicyDocument> policies) {
        var properties = new RateLimitProperties();
        return new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(policies, store, properties.getOnRedisError()),
                new RateLimitIdentityResolver(properties),
                new RateLimitMetrics(new SimpleMeterRegistry()),
                properties, new ObjectMapper(), Clock.systemUTC(), noExemptions(),
                new com.example.ratelimit.policy.EndpointExemptionService(null) {
                    @Override
                    public boolean isExempt(String method, String path) {
                        return false;
                    }
                });
    }

    private RateLimitFilter groupFilter(PolicyGroup group, GlobalScopeRules globalRules,
            List<PolicyDocument> compatibilityPolicies) {
        var properties = new RateLimitProperties();
        var managed = new ManagedPolicyStore(null, new ObjectMapper()) {
            @Override public List<PolicyGroup> findAllGroups() { return List.of(group); }
            @Override public Optional<GlobalScopeRules> findGlobalRules() { return Optional.of(globalRules); }
            @Override public List<PolicyDocument> findAll() { return compatibilityPolicies; }
        };
        var projections = new ProjectionService();
        var matcher = new PolicyMatcher(managed, properties, projections);
        var enforcer = new PolicyEnforcer(matcher, store, properties);
        return new RateLimitFilter(enforcer, new RateLimitIdentityResolver(properties),
                new RateLimitMetrics(new SimpleMeterRegistry()), properties, new ObjectMapper(),
                Clock.systemUTC(), noExemptions(), new EndpointExemptionService(null) {
                    @Override public boolean isExempt(String method, String path) { return false; }
                });
    }

    private static PolicyDocument doc(String id, String method, String path, Scope scope, int limit) {
        var now = Instant.now();
        return PolicyDocument.builder(id)
                .name(id).route(method, path)
                .algorithm(Algorithm.FIXED_WINDOW).scope(scope)
                .window(Duration.ofMinutes(1), limit)
                .version(1).timestamps(now, now).build();
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path,
            String remoteAddr) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response;
    }

    private static void authenticateAsAlice() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void applicationPolicySharesOneQuotaAcrossRoutesAndClients() throws Exception {
        var filter = filter(List.of(doc("site-wide", "GET", null, Scope.APPLICATION, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        var second = call(filter, "GET", "/api/orders", "198.51.100.7");
        assertThat(second.getStatus()).as("a different client on a different route shares the quota")
                .isEqualTo(429);
        assertThat(second.getHeader("X-RateLimit-Policy")).isEqualTo("site-wide");
        assertThat(store.counts).as("one key, charged once").hasSize(1);
        assertThat(store.consumes).isEqualTo(1);
    }

    @Test
    void distinctApplicationPoliciesUseDistinctCounters() throws Exception {
        var filter = filter(List.of(
                doc("app-a", null, null, Scope.APPLICATION, 1),
                doc("app-b", null, null, Scope.APPLICATION, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        // app-a is exhausted, but app-b has its own quota
        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(429);
        assertThat(store.counts).as("two separate counters").hasSize(2);
    }

    @Test
    void exemptionBypassesAllPolicies() throws Exception {
        var exemption = ExemptionDocument.builder("ex-products")
                .name("ex-products")
                .route("GET", "/api/products")
                .enabled(true)
                .version(1)
                .timestamps(Instant.now(), Instant.now())
                .updatedBy("test")
                .build();
        var properties = new RateLimitProperties();
        var exemptions = new ExemptionStore(null, new ObjectMapper()) {
            @Override
            public java.util.List<ExemptionDocument> findAll() {
                return java.util.List.of(exemption);
            }
        };
        var filterWithExemption = new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(List.of(doc("site-wide", "GET", "/api/*", Scope.IP, 1)), store, properties.getOnRedisError()),
                new RateLimitIdentityResolver(properties),
                new RateLimitMetrics(new SimpleMeterRegistry()),
                properties, new ObjectMapper(), Clock.systemUTC(),
                exemptions,
                new com.example.ratelimit.policy.EndpointExemptionService(null) {
                    @Override
                    public boolean isExempt(String method, String path) {
                        return false;
                    }
                });

        assertThat(call(filterWithExemption, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(call(filterWithExemption, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(store.consumes).as("exempted requests do not charge quota").isZero();

        assertThat(call(filterWithExemption, "GET", "/api/orders", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(call(filterWithExemption, "GET", "/api/orders", "203.0.113.1").getStatus()).isEqualTo(429);
    }

    @Test
    void exemptionValidationRejectsBroadPatterns() {
        var tooBroad = ExemptionDocument.builder("ex-bad")
                .name("ex-bad")
                .route("ANY", "/**")
                .enabled(true)
                .version(1)
                .timestamps(Instant.now(), Instant.now())
                .updatedBy("test")
                .build();
        try {
            tooBroad.validate();
            throw new AssertionError("expected PolicyValidationException");
        } catch (PolicyValidationException e) {
            assertThat(e.problems()).contains("path /** is too broad; use a specific route");
        }
    }

    @Test
    void exemptionValidationRejectsAdminPaths() {
        var adminExempt = ExemptionDocument.builder("ex-admin")
                .name("ex-admin")
                .route("ANY", "/api/admin/policies")
                .enabled(true)
                .version(1)
                .timestamps(Instant.now(), Instant.now())
                .updatedBy("test")
                .build();
        try {
            adminExempt.validate();
            throw new AssertionError("expected PolicyValidationException");
        } catch (PolicyValidationException e) {
            assertThat(e.problems()).contains("exemptions cannot target admin or actuator routes");
        }
    }

    @Test
    void globalPolicySharesOneQuotaAcrossDifferentClients() throws Exception {
        var filter = filter(List.of(doc("site-wide", "GET", null, Scope.GLOBAL, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        var second = call(filter, "GET", "/api/orders", "198.51.100.7");
        assertThat(second.getStatus()).as("a different client on a different route shares the quota")
                .isEqualTo(429);
        assertThat(second.getHeader("X-RateLimit-Policy")).isEqualTo("site-wide");
        assertThat(store.counts).as("one key, charged once").hasSize(1);
        assertThat(store.consumes).isEqualTo(1);
    }

    @Test
    void userPolicyOnAWidePatternSpansEndpoints() throws Exception {
        authenticateAsAlice();
        var filter = filter(List.of(doc("user-wide", "POST", "/api/*", Scope.USER, 2)));

        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/b", "203.0.113.1").getStatus())
                .as("second endpoint draws from the same user quota").isEqualTo(200);
        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(429);
        assertThat(store.consumes).isEqualTo(2);
    }

    @Test
    void differentUsersDoNotShareAUserQuota() throws Exception {
        authenticateAsAlice();
        var filter = filter(List.of(doc("per-user", "POST", "/api/*", Scope.USER, 1)));
        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(200);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(call(filter, "POST", "/api/a", "203.0.113.9").getStatus())
                .as("bob has his own quota").isEqualTo(200);
    }

    @Test
    void groupRulesComposeWithLegacyRulesWithoutChargingCompatibilityProjectionsTwice() throws Exception {
        var endpointRules = List.of(
                new ScopeRule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 10,
                        null, null, null, null, null, null, null, null),
                new ScopeRule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 50,
                        null, null, null, null, null, null, null, null));
        var endpoint = new EndpointRule("ep-compose", "GET", "/api/products", "Products",
                true, false, endpointRules);
        var now = Instant.now();
        var group = new PolicyGroup("group-compose", "Products", true, List.of(endpoint),
                RateLimitProperties.FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var globalRules = new GlobalScopeRules(List.of(
                new ScopeRule(Scope.APPLICATION, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100,
                        null, null, null, null, null, null, null, null),
                new ScopeRule(Scope.GLOBAL, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 200,
                        null, null, null, null, null, null, null, null)),
                RateLimitProperties.FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var projections = new ProjectionService();
        var compatibility = new java.util.ArrayList<PolicyDocument>();
        compatibility.addAll(projections.projectGroup(group));
        compatibility.addAll(projections.projectGlobalRules(globalRules));
        compatibility.add(doc("legacy-products", "GET", "/api/products", Scope.IP, 500));
        var groupFilter = groupFilter(group, globalRules, compatibility);

        assertThat(call(groupFilter, "GET", "/api/products", "203.0.113.9").getStatus()).isEqualTo(200);
        assertThat(store.consumes).as("two group scopes, two distinct global scopes, and one legacy policy")
                .isEqualTo(5);
        assertThat(store.counts).hasSize(5);
    }

}
