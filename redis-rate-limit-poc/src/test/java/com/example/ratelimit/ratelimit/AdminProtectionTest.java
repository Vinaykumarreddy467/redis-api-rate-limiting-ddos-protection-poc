package com.example.ratelimit.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The fixed control-plane throttle: brute-force lockout, request cap, fail-closed, and not editable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.admin.username=admin-test",
                "ratelimit.admin.password=admin-test-secret",
                "ratelimit.admin.raw-password=true",
                "rate-limit.admin-protection.limit=8",
                "rate-limit.admin-protection.window=10m",
                "rate-limit.admin-protection.max-auth-failures=3",
                "rate-limit.admin-protection.failure-window=10m",
                "rate-limit.admin-protection.lockout=10m"
        })
@Import(AdminProtectionTest.RedisConfig.class)
class AdminProtectionTest {

    private static final String POLICIES = "/api/admin/rate-limit/policies";

    @TestConfiguration
    static class RedisConfig {
        @Bean
        @Primary
        LettuceConnectionFactory testConnectionFactory() {
            var container = RedisTestSupport.redis();
            var config = new RedisStandaloneConfiguration(container.getHost(),
                    container.getMappedPort(RedisTestSupport.REDIS_PORT));
            var factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        Set<String> keys = redis.keys("*control-plane-*");
        Set<String> cp = redis.keys("*:cp:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        if (cp != null && !cp.isEmpty()) {
            redis.delete(cp);
        }
    }

    private ResponseEntity<String> getPath(String path, String credentials) {
        var headers = new HttpHeaders();
        if (credentials != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                    + java.util.Base64.getEncoder().encodeToString(credentials.getBytes()));
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> get(String credentials) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                + java.util.Base64.getEncoder().encodeToString(credentials.getBytes()));
        return rest.exchange(POLICIES, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void normalAdminUsageUnderTheLimitWorks() {
        for (int i = 0; i < 4; i++) {
            assertThat(get("admin-test:admin-test-secret").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void burstAboveTheLimitGets429WithRetryAfter() {
        for (int i = 0; i < 8; i++) {
            assertThat(get("admin-test:admin-test-secret").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var over = get("admin-test:admin-test-secret");
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(over.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(over.getBody()).contains("\"status\":429").contains("retryAfterSeconds");
    }

    @Test
    void repeatedWrongPasswordsAreLockedOutEvenForTheRightPassword() {
        for (int i = 0; i < 3; i++) {
            assertThat(get("admin-test:wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var locked = get("admin-test:wrong");
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isGreaterThan(60);
        assertThat(get("admin-test:admin-test-secret").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void wrongPasswordsOnAnyPathCountTowardTheLockout() {
        assertThat(getPath("/api/orders", "admin-test:wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getPath("/actuator/health", "admin-test:wrong").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getPath("/api/orders", "nobody:wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("admin-test:admin-test-secret").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void requestsWithoutCredentialsNeverLockTheAdminOut() {
        for (int i = 0; i < 5; i++) {
            assertThat(getPath(POLICIES, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(get("admin-test:admin-test-secret").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pocPathsShareTheRequestCap() {
        for (int i = 0; i < 8; i++) {
            assertThat(getPath("/api/poc/anything", null).getStatusCode())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(getPath("/api/poc/anything", null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void lockoutLastsTheFullDurationAndSparesPublicRoutes() {
        for (int i = 0; i < 3; i++) {
            get("admin-test:wrong");
        }
        var locked = get("admin-test:wrong");
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(590L, 600L);
        Set<String> lockKeys = redis.keys("*:cp:lock:*");
        assertThat(lockKeys).hasSize(1);
        Long ttl = redis.getExpire(lockKeys.iterator().next());
        assertThat(ttl).isBetween(590L, 600L);
        assertThat(redis.keys("*:cp:fail:*")).as("counter resets when the lock is set").isEmpty();
        assertThat(getPath("/api/products", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pathsAreNormalisedBeforeMatching() throws Exception {
        var consumed = new java.util.ArrayList<String>();
        RateLimitStore counting = new RateLimitStore() {
            @Override
            public RateLimitDecision consume(Policy p, String t, String i, long n) {
                consumed.add(p.id());
                return RateLimitDecision.allow(p.limit(), 1);
            }

            @Override
            public RateLimitDecision peek(Policy p, String t, String i, long n) {
                throw new AssertionError("no peek expected");
            }
        };
        var properties = new RateLimitProperties();
        var filter = new AdminProtectionFilter(counting, new RateLimitIdentityResolver(properties), properties,
                new ObjectMapper(), Clock.systemUTC());
        for (String path : new String[] {"//api/admin/x", "/api/admin", "/api/poc", "/api//poc/x"}) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        assertThat(consumed).hasSize(4);
        var publicRequest = new MockHttpServletRequest("GET", "/api/products");
        publicRequest.setServletPath("/api/products");
        filter.doFilter(publicRequest, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(consumed).as("public, credential-less request never touches Redis here").hasSize(4);
    }

    @Test
    void theLimitIsNotVisibleOrEditableThroughThePolicyApi() {
        var list = get("admin-test:admin-test-secret");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).doesNotContain("control-plane");
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin-test:admin-test-secret".getBytes()));
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.DELETE}) {
            var response = rest.exchange(POLICIES + "/control-plane-requests", method,
                    new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        var patch = rest.exchange(POLICIES + "/control-plane-requests/enabled", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false), headers), String.class);
        assertThat(patch.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void redisOutageRefusesAdminPathsWith503() throws Exception {
        RateLimitStore down = new RateLimitStore() {
            @Override
            public java.time.Duration controlPlaneLockRemaining(String ip) {
                throw new RateLimitStoreUnavailableException("down", null);
            }

            @Override
            public com.example.ratelimit.ratelimit.RateLimitDecision consume(Policy p, String t, String i, long n) {
                throw new RateLimitStoreUnavailableException("down", null);
            }

            @Override
            public com.example.ratelimit.ratelimit.RateLimitDecision peek(Policy p, String t, String i, long n) {
                throw new RateLimitStoreUnavailableException("down", null);
            }
        };
        var properties = new RateLimitProperties();
        var filter = new AdminProtectionFilter(down, new RateLimitIdentityResolver(properties), properties,
                new ObjectMapper(), Clock.systemUTC());
        var request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic eDp5");
        request.setRequestURI("/api/orders");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).as("request must not reach authentication").isNull();
        var admin = new MockHttpServletRequest("GET", "/api/admin/x");
        admin.setServletPath("/api/admin/x");
        var adminResponse = new MockHttpServletResponse();
        filter.doFilter(admin, adminResponse, new MockFilterChain());
        assertThat(adminResponse.getStatus()).isEqualTo(503);
    }
}
