package com.example.ratelimit.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Documented Redis-failure behaviour, exercised without Redis by a stub store that always throws. */
class RateLimitRedisFailureTest {

    private static final Policy SENSITIVE = new Policy("login-attempt", "POST", "/api/login", 10,
            Duration.ofMinutes(1), Identity.IP, FailureMode.FAIL_CLOSED);
    private static final Policy PUBLIC = new Policy("products-read", "GET", "/api/products", 100,
            Duration.ofMinutes(1), Identity.IP, null);

    private static com.example.ratelimit.policy.ExemptionStore noExemptions() {
        return new com.example.ratelimit.policy.ExemptionStore(null, new ObjectMapper()) {
            @Override
            public java.util.List<com.example.ratelimit.policy.ExemptionDocument> findAll() {
                return java.util.List.of();
            }
        };
    }

    private static RateLimitFilter filter(FailureMode global, List<Policy> policies, RateLimitStore store) {
        var properties = new RateLimitProperties();
        properties.setOnRedisError(global);
        properties.setPolicies(policies);
        return new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(PolicyMatcher.fromYamlProperties(properties), store, global),
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

    private static RateLimitFilter filter(FailureMode global, List<Policy> policies, boolean enabled,
            RateLimitStore store) {
        var properties = new RateLimitProperties();
        properties.setEnabled(enabled);
        properties.setOnRedisError(global);
        properties.setPolicies(policies);
        return new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(PolicyMatcher.fromYamlProperties(properties), store, global),
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

    private static RateLimitStore alwaysFails() {
        return new RateLimitStore() {
            @Override
            public RateLimitDecision consume(Policy policy, String type, String identity, long now) {
                throw new RateLimitStore.RateLimitStoreUnavailableException("simulated outage", null);
            }

            @Override
            public RateLimitDecision peek(Policy policy, String type, String identity, long now) {
                throw new RateLimitStore.RateLimitStoreUnavailableException("simulated outage", null);
            }
        };
    }

    private static MockHttpServletResponse run(RateLimitFilter filter, String method, String path,
            boolean expectChain) throws ServletException, IOException {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.1");
        var response = new MockHttpServletResponse();
        boolean[] reachedController = {false};
        filter.doFilter(request, response, (req, res) -> {
            reachedController[0] = true;
            ((MockHttpServletResponse) res).setStatus(200);
        });
        assertThat(reachedController[0]).as("request reached the application").isEqualTo(expectChain);
        return response;
    }

    @Test
    void failClosedRejectsWith503() throws Exception {
        var response = run(filter(FailureMode.FAIL_OPEN, List.of(SENSITIVE), alwaysFails()),
                "POST", "/api/login", false);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Rate limiting is temporarily unavailable");
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void failOpenLetsTheRequestThrough() throws Exception {
        var response = run(filter(FailureMode.FAIL_OPEN, List.of(PUBLIC), alwaysFails()),
                "GET", "/api/products", true);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void globalFailClosedAppliesWhenThePolicyDoesNotOverride() throws Exception {
        var response = run(filter(FailureMode.FAIL_CLOSED, List.of(PUBLIC), alwaysFails()),
                "GET", "/api/products", false);
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void disabledLimiterNeverCallsTheStore() throws Exception {
        var store = new CountingStore();
        var response = run(filter(FailureMode.FAIL_CLOSED, List.of(PUBLIC), false, store),
                "GET", "/api/products", true);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(store.calls).isZero();
    }

    @Test
    void unlistedRouteIsNotCounted() throws Exception {
        var store = new CountingStore();
        run(filter(FailureMode.FAIL_OPEN, List.of(PUBLIC), true, store), "GET", "/api/unknown", true);
        assertThat(store.calls).isZero();
    }

    @Test
    void failClosedResponseMakesNoQuotaClaim() throws Exception {
        var response = run(filter(FailureMode.FAIL_OPEN, List.of(SENSITIVE), alwaysFails()),
                "POST", "/api/login", false);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-RateLimit-Remaining"))
                .as("no counter was read, so no remaining-quota claim is made")
                .isNull();
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        assertThat(response.getHeader("X-RateLimit-Policy")).isEqualTo("login-attempt");
    }

    @Test
    void aRedisOutageDoesNotAffectRoutesWithNoPolicy() throws Exception {
        // Redis is down for every policy, but an unmatched route still reaches the application.
        var response = run(filter(FailureMode.FAIL_CLOSED, List.of(SENSITIVE), alwaysFails()),
                "GET", "/actuator/health", true);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void mostSpecificPolicyWins() {
        var properties = new RateLimitProperties();
        properties.setPolicies(List.of(PUBLIC,
                new Policy("product-detail", "GET", "/api/products/*", 5, Duration.ofMinutes(1),
                        Identity.IP, null)));
        var resolver = new RateLimitPolicyResolver(properties);
        assertThat(resolver.resolve("GET", "/api/products").id()).isEqualTo("products-read");
        assertThat(resolver.resolve("GET", "/api/products/42").id()).isEqualTo("product-detail");
        assertThat(resolver.resolve("DELETE", "/api/products")).isNull();
    }

    @Test
    void duplicatePolicyIdFailsStartup() {
        var properties = new RateLimitProperties();
        properties.setPolicies(List.of(PUBLIC, PUBLIC));
        assertThatThrownBy(() -> new RateLimitPolicyResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate policy id");
    }

    private static final class CountingStore implements RateLimitStore {
        int calls;

        @Override
        public RateLimitDecision consume(Policy policy, String identityType, String identity, long now) {
            calls++;
            return RateLimitDecision.allow(policy.limit(), policy.limit() - 1);
        }

        @Override
        public RateLimitDecision peek(Policy policy, String identityType, String identity, long now) {
            return RateLimitDecision.allow(policy.limit(), policy.limit());
        }
    }
}
