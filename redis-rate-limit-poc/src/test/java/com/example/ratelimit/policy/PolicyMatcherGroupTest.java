package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyMatcherGroupTest {

    private final ProjectionService projections = new ProjectionService();

    private static ScopeRule rule(Scope scope, int limit) {
        return new ScopeRule(scope, Algorithm.FIXED_WINDOW, Duration.ofMinutes(1), limit,
                null, null, null, null, null, null, null, null);
    }

    private static PolicyGroup group(String id, boolean enabled, List<ScopeRule> rules) {
        var now = Instant.now();
        var endpoint = new EndpointRule("ep-" + id, "GET", "/api/products", id,
                true, false, rules);
        return new PolicyGroup(id, id, enabled, List.of(endpoint), FailureMode.FAIL_OPEN,
                1, now, now, "test", List.of());
    }

    private static PolicyDocument legacyPolicy() {
        var now = Instant.now();
        return PolicyDocument.builder("legacy-products")
                .name("Legacy products").route("GET", "/api/products")
                .algorithm(Algorithm.FIXED_WINDOW).scope(Scope.IP)
                .window(Duration.ofMinutes(1), 100).version(1)
                .timestamps(now, now).updatedBy("test").build();
    }

    @Test
    void groupsAndGlobalRulesAreMatchedOnceAlongsideLegacyPolicies() {
        var active = group("group-active", true,
                List.of(rule(Scope.ENDPOINT, 10), rule(Scope.IP, 50)));
        var disabled = group("group-disabled", false, List.of(rule(Scope.ENDPOINT, 2)));
        var now = Instant.now();
        var global = new GlobalScopeRules(List.of(rule(Scope.APPLICATION, 1000), rule(Scope.GLOBAL, 2000)),
                FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var compatibilityDocs = new java.util.ArrayList<PolicyDocument>();
        compatibilityDocs.addAll(projections.projectGroup(active));
        compatibilityDocs.addAll(projections.projectGroup(disabled));
        compatibilityDocs.addAll(projections.projectGlobalRules(global));
        compatibilityDocs.add(legacyPolicy());

        var store = new ManagedPolicyStore(null, new ObjectMapper()) {
            @Override public List<PolicyGroup> findAllGroups() { return List.of(active, disabled); }
            @Override public Optional<GlobalScopeRules> findGlobalRules() { return Optional.of(global); }
            @Override public List<PolicyDocument> findAll() { return compatibilityDocs; }
        };
        var matcher = new PolicyMatcher(store, new RateLimitProperties(), projections);

        var matched = matcher.matching("GET", "/api/products");
        var ids = matched.stream().map(PolicyDocument::id).toList();

        assertThat(ids).contains(
                projections.projectionId("group-active", "ep-group-active", Scope.ENDPOINT),
                projections.projectionId("group-active", "ep-group-active", Scope.IP),
                projections.globalProjectionId(Scope.APPLICATION),
                projections.globalProjectionId(Scope.GLOBAL),
                "legacy-products");
        assertThat(ids).doesNotContain(projections.projectionId(
                "group-disabled", "ep-group-disabled", Scope.ENDPOINT));
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).hasSize(5);
    }

    @Test
    void endpointRulesRespectMethodAndPathAndGlobalRulesRemainGlobal() {
        var active = group("group-route", true, List.of(rule(Scope.ENDPOINT, 10)));
        var now = Instant.now();
        var global = new GlobalScopeRules(List.of(rule(Scope.GLOBAL, 2000)), FailureMode.FAIL_OPEN,
                1, now, now, "test", List.of());
        var store = new ManagedPolicyStore(null, new ObjectMapper()) {
            @Override public List<PolicyGroup> findAllGroups() { return List.of(active); }
            @Override public Optional<GlobalScopeRules> findGlobalRules() { return Optional.of(global); }
            @Override public List<PolicyDocument> findAll() { return List.of(); }
        };
        var matcher = new PolicyMatcher(store, new RateLimitProperties(), projections);

        assertThat(matcher.matching("POST", "/api/products")).extracting(PolicyDocument::scope)
                .containsExactly(Scope.GLOBAL);
        assertThat(matcher.matching("GET", "/api/other")).extracting(PolicyDocument::scope)
                .containsExactly(Scope.GLOBAL);
        assertThat(matcher.matching("GET", "/api/products")).extracting(PolicyDocument::scope)
                .containsExactly(Scope.ENDPOINT, Scope.GLOBAL);
    }

    @Test
    void overlappingRoutesUseAntSpecificityAfterFilteringByMethod() {
        var now = Instant.now();
        var broad = new EndpointRule("broad", "GET", "/api/{name}", "Broad", true, false,
                List.of(rule(Scope.ENDPOINT, 10)));
        var literal = new EndpointRule("literal", "GET", "/api/products", "Literal", true, false,
                List.of(rule(Scope.ENDPOINT, 20)));
        var wrongMethod = new EndpointRule("post", "POST", "/api/products", "Post", true, false,
                List.of(rule(Scope.ENDPOINT, 30)));
        var group = new PolicyGroup("routes", "Routes", true, List.of(broad, literal, wrongMethod),
                FailureMode.FAIL_OPEN, 1, now, now, "test", List.of());
        var store = new ManagedPolicyStore(null, new ObjectMapper()) {
            @Override public List<PolicyGroup> findAllGroups() { return List.of(group); }
            @Override public Optional<GlobalScopeRules> findGlobalRules() { return Optional.empty(); }
            @Override public List<PolicyDocument> findAll() { return List.of(); }
        };
        var matcher = new PolicyMatcher(store, new RateLimitProperties(), projections);

        assertThat(matcher.matching("GET", "/api/products")).extracting(PolicyDocument::id)
                .containsExactly(projections.projectionId("routes", "literal", Scope.ENDPOINT),
                        projections.projectionId("routes", "broad", Scope.ENDPOINT));
        assertThat(matcher.matching("POST", "/api/products")).extracting(PolicyDocument::id)
                .containsExactly(projections.projectionId("routes", "post", Scope.ENDPOINT));
    }
}
