package com.example.ratelimit.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Identity;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import com.example.ratelimit.policy.PolicyEnforcer;
import com.example.ratelimit.policy.PolicyMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Window-boundary behaviour through the HTTP layer, driven by a controllable clock so no test
 * sleeps waiting for a real window to roll over. The fake store reproduces the real key layout and
 * TTL maths; the Lua script itself is covered against Redis by RedisRateLimitStoreTest.
 */
class RateLimitWindowBoundaryTest {

    /** Window-aligned epoch millis (a multiple of 60_000). */
    private static final long T0 = 1_789_762_740_000L;
    private static final long WINDOW_MILLIS = 60_000;

    private static final Policy POLICY = new Policy("boundary", "GET", "/api/boundary", 1,
            Duration.ofMinutes(1), Identity.IP, null);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochMilli(T0));
    private final RecordingStore store = new RecordingStore();

    @Test
    void quotaIsFreshInEveryWindow() throws Exception {
        var filter = filter();

        assertThat(get(filter, T0, "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(get(filter, T0 + 1_000, "203.0.113.1").getStatus()).isEqualTo(429);

        long nextWindow = T0 + WINDOW_MILLIS;
        assertThat(get(filter, nextWindow, "203.0.113.1").getStatus()).as("fresh quota, new window").isEqualTo(200);
        assertThat(store.keysFor("203.0.113.1"))
                .as("the window id is part of the key, so a new window uses a new key")
                .hasSize(2)
                .doesNotHaveDuplicates();
    }

    @Test
    void retryAfterReflectsTheRealTimeLeftInTheWindow() throws Exception {
        var filter = filter();
        // Two different clients, both rejected inside the same window but at different times.
        // Limit is 1, so the first request per client is allowed and the second is rejected.
        assertThat(get(filter, T0, "203.0.113.1").getStatus()).isEqualTo(200);
        long earlyRetry = Long.parseLong(get(filter, T0, "203.0.113.1").getHeader("Retry-After"));

        assertThat(get(filter, T0 + 45_000, "203.0.113.2").getStatus()).isEqualTo(200);
        long lateRetry = Long.parseLong(get(filter, T0 + 45_000, "203.0.113.2").getHeader("Retry-After"));

        assertThat(earlyRetry).as("at the window start the whole window is left").isBetween(58L, 61L);
        assertThat(lateRetry).as("45s in, about 15s remain").isBetween(1L, 16L);
        assertThat(lateRetry).isLessThan(earlyRetry);
        assertThat(lateRetry).as("never invalid").isGreaterThanOrEqualTo(1L);
    }

    @Test
    void keysCarryPolicyAndHashedIdentityNotTheRawPath() throws Exception {
        var filter = filter();
        get(filter, T0, "203.0.113.1");
        assertThat(store.lastKey).contains("boundary:ip:").doesNotContain("/api/boundary");
        assertThat(store.lastIdentity).isEqualTo("203.0.113.1");
        assertThat(store.lastKey).doesNotContain("203.0.113.1");
    }

    @Test
    void deniedPreflightDoesNotChargeEarlierPolicies() throws Exception {
        var first = new Policy("boundary-first", "GET", "/api/boundary", 2,
                Duration.ofMinutes(1), Identity.IP, null);
        var second = new Policy("boundary-second", "GET", "/api/boundary", 1,
                Duration.ofMinutes(1), Identity.IP, null);
        var filter = filter(List.of(first, second));

        assertThat(get(filter, T0, "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(store.consumes).isEqualTo(2);

        // The second policy is already exhausted, so its preflight denies before either policy commits.
        assertThat(get(filter, T0 + 1_000, "203.0.113.7").getStatus()).isEqualTo(429);
        assertThat(store.consumes)
                .as("a preflight denial must not spend quota in earlier policies")
                .isEqualTo(2);
    }

    private RateLimitFilter filter() {
        return filter(List.of(POLICY));
    }

    private RateLimitFilter filter(List<Policy> policies) {
        var properties = new RateLimitProperties();
        properties.setPolicies(policies);
        var clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public long millis() {
                return now.get().toEpochMilli();
            }
        };
        return new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(PolicyMatcher.fromYamlProperties(properties), store,
                        properties.getOnRedisError()),
                new RateLimitIdentityResolver(properties),
                new RateLimitMetrics(new SimpleMeterRegistry()),
                properties, new ObjectMapper(), clock,
                new com.example.ratelimit.policy.ExemptionStore(null, new ObjectMapper()) {
                    @Override
                    public java.util.List<com.example.ratelimit.policy.ExemptionDocument> findAll() {
                        return java.util.List.of();
                    }
                },
                new com.example.ratelimit.policy.EndpointExemptionService(null) {
                    @Override
                    public boolean isExempt(String method, String path) {
                        return false;
                    }
                });
    }

    private MockHttpServletResponse get(RateLimitFilter filter, long millis, String clientIp)
            throws ServletException, IOException {
        now.set(Instant.ofEpochMilli(millis));
        var request = new MockHttpServletRequest("GET", "/api/boundary");
        request.setRemoteAddr(clientIp);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response;
    }

    /** In-memory stand-in reproducing the real key layout and window maths. */
    private static final class RecordingStore implements RateLimitStore {
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, List<String>> keysByIdentity = new HashMap<>();
        String lastKey = "";
        String lastIdentity = "";
        int consumes = 0;

        List<String> keysFor(String identity) {
            return keysByIdentity.getOrDefault(RedisRateLimitStore.hash(identity), List.of());
        }

        @Override
        public RateLimitDecision consume(Policy policy, String identityType, String identity, long nowMillis) {
            long windowMillis = policy.window().toMillis();
            long elapsed = Math.floorMod(nowMillis, windowMillis);
            String key = "%s:%s:%s:%d".formatted(policy.id(), identityType.toLowerCase(),
                    RedisRateLimitStore.hash(identity), Math.floorDiv(nowMillis, windowMillis));
            lastKey = key;
            lastIdentity = identity;
            keysByIdentity.computeIfAbsent(RedisRateLimitStore.hash(identity), k -> new ArrayList<>());
            if (!keysByIdentity.get(RedisRateLimitStore.hash(identity)).contains(key)) {
                keysByIdentity.get(RedisRateLimitStore.hash(identity)).add(key);
            }

            int count = counts.merge(key, 1, Integer::sum);
            consumes++;
            if (count <= policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            long retryAfter = Math.max(1, Math.ceilDiv(windowMillis - elapsed, 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        }

        @Override
        public RateLimitDecision peek(Policy policy, String identityType, String identity, long nowMillis) {
            long windowMillis = policy.window().toMillis();
            long elapsed = Math.floorMod(nowMillis, windowMillis);
            String key = "%s:%s:%s:%d".formatted(policy.id(), identityType.toLowerCase(),
                    RedisRateLimitStore.hash(identity), Math.floorDiv(nowMillis, windowMillis));
            int count = counts.getOrDefault(key, 0);
            if (count < policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            long retryAfter = Math.max(1, Math.ceilDiv(windowMillis - elapsed, 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        }
    }
}
