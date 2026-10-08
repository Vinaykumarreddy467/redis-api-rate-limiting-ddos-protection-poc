package com.example.ratelimit.policy;

import java.time.Duration;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyGroupValidatorTest {

    private final PolicyGroupValidator validator = new PolicyGroupValidator();

    private static ScopeRule rule(Scope scope, Algorithm algorithm, Duration window, Integer limit) {
        return new ScopeRule(scope, algorithm, window, limit, null, null, null, null, null, null, null, null);
    }

    private static ScopeRule tbRule(Scope scope, Integer capacity, Duration refillInterval) {
        return new ScopeRule(scope, Algorithm.TOKEN_BUCKET, null, null, capacity, refillInterval, 1, null, null, null, null, null);
    }

    private static ScopeRule lbRule(Scope scope, Integer drainRate) {
        return new ScopeRule(scope, Algorithm.LEAKY_BUCKET, null, null, null, null, null, drainRate, 10, null, null, null);
    }

    private static EndpointRule endpoint(String path, ScopeRule... rules) {
        return new EndpointRule("ep-" + path, "GET", path, path, true, false, List.of(rules));
    }

    private static PolicyGroup group(EndpointRule... endpoints) {
        return new PolicyGroup("grp-test", "Test", true, List.of(endpoints),
                FailureMode.FAIL_OPEN, 1, java.time.Instant.now(), java.time.Instant.now(), "test", List.of());
    }

    private static GlobalScopeRules global(ScopeRule... rules) {
        return new GlobalScopeRules(List.of(rules), FailureMode.FAIL_OPEN, 1,
                java.time.Instant.now(), java.time.Instant.now(), "test", List.of());
    }

    @Test
    void validHierarchyPasses() {
        var ep = endpoint("/api/products",
                rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000));
        var g = group(ep);
        var global = global();
        validator.validateGroup(g, global);
    }

    @Test
    void endpointRpsMustBeStrictlyLessThanIpRps() {
        var ep = endpoint("/api/products",
                rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100));
        var g = group(ep);
        assertThatThrownBy(() -> validator.validateGroup(g, global()))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("ENDPOINT RPS");
    }

    @Test
    void endpointRpsEqualToIpRpsIsRejected() {
        var ep = endpoint("/api/products",
                rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100));
        var g = group(ep);
        assertThatThrownBy(() -> validator.validateGroup(g, global()))
                .isInstanceOf(PolicyValidationException.class);
    }

    @Test
    void missingScopesSkipComparison() {
        var ep = endpoint("/api/products",
                rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000));
        var g = group(ep);
        validator.validateGroup(g, global());
    }

    @Test
    void exemptEndpointsSkipValidation() {
        var ep = new EndpointRule("ep-exempt", "GET", "/api/exempt", "Exempt", true, true,
                List.of(rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000)));
        var g = group(ep);
        validator.validateGroup(g, global());
    }

    @Test
    void tokenBucketRpsIsCapacityDividedByRefillInterval() {
        var ep = endpoint("/api/products",
                tbRule(Scope.ENDPOINT, 100, Duration.ofSeconds(10)),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000));
        var g = group(ep);
        validator.validateGroup(g, global());
    }

    @Test
    void tokenBucketRpsViolationIsDetected() {
        var ep = endpoint("/api/products",
                tbRule(Scope.ENDPOINT, 1000, Duration.ofSeconds(1)),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100));
        var g = group(ep);
        assertThatThrownBy(() -> validator.validateGroup(g, global()))
                .isInstanceOf(PolicyValidationException.class);
    }

    @Test
    void leakyBucketRpsIsDrainRate() {
        var ep = endpoint("/api/products",
                lbRule(Scope.ENDPOINT, 50),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 10000));
        var g = group(ep);
        validator.validateGroup(g, global());
    }

    @Test
    void concurrencyLimitIsNonComparable() {
        var ep = endpoint("/api/products",
                new ScopeRule(Scope.ENDPOINT, Algorithm.CONCURRENCY_LIMIT, null, null, null, null, null, null, null, 100, Duration.ofMinutes(1), null),
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 10));
        var g = group(ep);
        validator.validateGroup(g, global());
    }

    @Test
    void endpointScopeRulesRejectApplication() {
        assertThatThrownBy(() -> new EndpointRule("ep-bad", "GET", "/api/bad", "Bad", true, false,
                List.of(rule(Scope.APPLICATION, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100))))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("APPLICATION");
    }

    @Test
    void endpointScopeRulesRejectGlobal() {
        assertThatThrownBy(() -> new EndpointRule("ep-bad", "GET", "/api/bad", "Bad", true, false,
                List.of(rule(Scope.GLOBAL, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100))))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("GLOBAL");
    }

    @Test
    void globalScopeRulesRejectEndpoint() {
        assertThatThrownBy(() -> new GlobalScopeRules(
                List.of(rule(Scope.ENDPOINT, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100)),
                FailureMode.FAIL_OPEN, 1, java.time.Instant.now(), java.time.Instant.now(), "test", List.of()))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("APPLICATION or GLOBAL");
    }

    @Test
    void globalRulesValidateAgainstAllGroups() {
        var ep = endpoint("/api/products",
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 500));
        var g = group(ep);
        var global = global(rule(Scope.APPLICATION, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100));
        assertThatThrownBy(() -> validator.validateGlobalRules(global, List.of(g)))
                .isInstanceOf(PolicyValidationException.class);
    }

    @Test
    void applicationRpsMustBeStrictlyLessThanGlobalRps() {
        var ep = endpoint("/api/products",
                rule(Scope.IP, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 100));
        var g = group(ep);
        var global = global(
                rule(Scope.APPLICATION, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 50),
                rule(Scope.GLOBAL, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), 1000));
        assertThatThrownBy(() -> validator.validateGlobalRules(global, List.of(g)))
                .isInstanceOf(PolicyValidationException.class);
    }
}
