package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointExemptionServiceTest {

    private static LettuceConnectionFactory factory;
    private ManagedPolicyStore store;
    private EndpointExemptionService service;
    private StringRedisTemplate redis;
    private ProjectionService projectionService;

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
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        store = new ManagedPolicyStore(redis, mapper);
        projectionService = new ProjectionService();
        service = new EndpointExemptionService(store);
        store.reset("test-setup");
    }

    @AfterEach
    void tearDown() {
        store.reset("test-teardown");
    }

    private static ScopeRule rule(Scope scope, int limit) {
        return new ScopeRule(scope, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), limit,
                null, null, null, null, null, null, null, null);
    }

    private static EndpointRule endpoint(String path, boolean exempt, ScopeRule... rules) {
        return new EndpointRule("ep-" + path, "GET", path, path, true, exempt, List.of(rules));
    }

    private PolicyGroup group(EndpointRule... endpoints) {
        return group(true, endpoints);
    }

    private PolicyGroup group(boolean enabled, EndpointRule... endpoints) {
        return new PolicyGroup("grp-test", "Test", enabled, List.of(endpoints),
                FailureMode.FAIL_OPEN, 1, Instant.now(), Instant.now(), "test", List.of());
    }

    @Test
    void exemptEndpointBypasses() {
        var g = group(endpoint("/api/exempt", true, rule(Scope.ENDPOINT, 100)));
        var projections = projectionService.projectGroup(g);
        store.saveGroup(g, null, "test", projections, store.getEpoch());
        assertThat(service.isExempt("GET", "/api/exempt")).isTrue();
    }

    @Test
    void nonExemptEndpointDoesNotBypass() {
        var g = group(endpoint("/api/limited", false, rule(Scope.ENDPOINT, 100)));
        var projections = projectionService.projectGroup(g);
        store.saveGroup(g, null, "test", projections, store.getEpoch());
        assertThat(service.isExempt("GET", "/api/limited")).isFalse();
    }

    @Test
    void disabledGroupDoesNotApplyEndpointExemption() {
        var g = group(false, endpoint("/api/exempt-disabled", true, rule(Scope.ENDPOINT, 100)));
        store.saveGroup(g, null, "test", projectionService.projectGroup(g), store.getEpoch());
        assertThat(service.isExempt("GET", "/api/exempt-disabled")).isFalse();
    }

    @Test
    void noGroupsMeansNoExemption() {
        assertThat(service.isExempt("GET", "/api/anything")).isFalse();
    }

    @Test
    void methodMismatchDoesNotBypass() {
        var g = group(new EndpointRule("ep-post", "POST", "/api/exempt", "Exempt", true, true,
                List.of(rule(Scope.ENDPOINT, 100))));
        var projections = projectionService.projectGroup(g);
        store.saveGroup(g, null, "test", projections, store.getEpoch());
        assertThat(service.isExempt("GET", "/api/exempt")).isFalse();
        assertThat(service.isExempt("POST", "/api/exempt")).isTrue();
    }

    @Test
    void anyMethodBypasses() {
        var g = group(new EndpointRule("ep-any", "ANY", "/api/exempt", "Exempt", true, true,
                List.of(rule(Scope.ENDPOINT, 100))));
        var projections = projectionService.projectGroup(g);
        store.saveGroup(g, null, "test", projections, store.getEpoch());
        assertThat(service.isExempt("GET", "/api/exempt")).isTrue();
        assertThat(service.isExempt("POST", "/api/exempt")).isTrue();
    }

    @Test
    void redisFailureFailsOpen() {
        assertThat(service.isExempt("GET", "/api/anything")).isFalse();
    }
}
