package com.example.ratelimit.admin;

import java.util.Map;

import com.example.ratelimit.RedisTestSupport;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration control plane over real HTTP: who may call it, and what a save does.
 *
 * <p>The central claim is that authorization lives in Spring Security and not in the controller, so the
 * tests drive it the way a browser or curl would. {@code alice} and {@code bob} hold {@code ROLE_USER}
 * and must be refused; only the configured administrator may read or change policy.
 *
 * <p>Administrator credentials are injected as properties here rather than committed in
 * {@code application.yml}, which ships no default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.admin.username=admin-test",
                "ratelimit.admin.password=admin-test-secret",
                "ratelimit.admin.raw-password=true"
        })
@Import(PolicyAdminControllerTest.RedisConfig.class)
class PolicyAdminControllerTest {

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

    private static final String ADMIN = "admin-test:admin-test-secret";
    private static final String DEMO_USER = "alice:alice-pw";

    @Autowired
    TestRestTemplate rest;

    private static HttpHeaders json() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders admin() {
        var headers = json();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " +
                java.util.Base64.getEncoder().encodeToString(ADMIN.getBytes()));
        return headers;
    }

    private static HttpHeaders demoUser() {
        var headers = json();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " +
                java.util.Base64.getEncoder().encodeToString(DEMO_USER.getBytes()));
        return headers;
    }

    @BeforeEach
    void wipe() {
        // Start each test from the seeded baseline so ids do not collide across methods.
        rest.exchange("/api/admin/rate-limit/policies/reset", HttpMethod.POST,
                new HttpEntity<>(Map.of(), admin()), String.class);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreBaseline() {
        // The Testcontainers Redis is shared. Leaving a policy behind would let it be picked up by the
        // enforcement path in another test class, so put the seeded baseline back.
        rest.exchange("/api/admin/rate-limit/policies/reset", HttpMethod.POST,
                new HttpEntity<>(Map.of(), admin()), String.class);
    }

    @Test
    void anonymousCallerIsRefused() {
        ResponseEntity<String> response = rest.getForEntity("/api/admin/rate-limit/policies", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void demoUserIsRefusedBecauseTheyAreNotAdministrators() {
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                new HttpEntity<>(demoUser()), String.class);
        assertThat(response.getStatusCode())
                .as("alice holds ROLE_USER and must not reach the control plane")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void administratorCanReadPolicies() {
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("products-read");
    }

    @Test
    void createThenUpdateThenDisableThenDelete() {
        var create = Map.of(
                "id", "search-route",
                "name", "search route",
                "method", "GET",
                "path", "/api/search",
                "algorithm", "FIXED_WINDOW",
                "scope", "IP",
                "window", "PT1M",
                "limit", 100,
                "enabled", true);
        ResponseEntity<String> created = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.POST,
                new HttpEntity<>(create, admin()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"version\":1");

        // Update at version 1 -> 2
        var update = Map.of(
                "id", "search-route",
                "method", "GET",
                "path", "/api/search",
                "algorithm", "FIXED_WINDOW",
                "scope", "IP",
                "window", "PT1M",
                "limit", 250,
                "enabled", true,
                "version", 1);
        ResponseEntity<String> updated = rest.exchange("/api/admin/rate-limit/policies/search-route",
                HttpMethod.PUT, new HttpEntity<>(update, admin()), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).contains("\"version\":2");

        // Disable at version 2 -> 3
        ResponseEntity<String> disabled = rest.exchange(
                "/api/admin/rate-limit/policies/search-route/enabled", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false, "version", 2), admin()), String.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).contains("\"enabled\":false");

        ResponseEntity<String> deleted = rest.exchange("/api/admin/rate-limit/policies/search-route",
                HttpMethod.DELETE, new HttpEntity<>(admin()), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> gone = rest.exchange("/api/admin/rate-limit/policies/search-route",
                HttpMethod.GET, new HttpEntity<>(admin()), String.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void legacyProjectionOperationsUpdateTheOwningGroupWithoutDroppingSiblingRules() {
        var endpointRules = java.util.List.of(
                Map.of("scope", "ENDPOINT", "algorithm", "FIXED_WINDOW", "window", "PT1M", "limit", 10),
                Map.of("scope", "IP", "algorithm", "FIXED_WINDOW", "window", "PT1M", "limit", 60));
        var endpoint = Map.of("id", "ep-legacy", "method", "GET", "path", "/api/legacy-group",
                "displayName", "Legacy endpoint", "repeatable", true, "exempt", false,
                "scopeRules", endpointRules);
        var group = Map.of("id", "group-legacy", "name", "Legacy group", "enabled", true,
                "onRedisError", "FAIL_OPEN", "endpoints", java.util.List.of(endpoint));
        var created = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(group, admin()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var projectionId = new com.example.ratelimit.policy.ProjectionService()
                .projectionId("group-legacy", "ep-legacy", com.example.ratelimit.policy.Scope.ENDPOINT);
        var listed = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(listed.getBody()).contains(projectionId);

        var update = Map.of("algorithm", "FIXED_WINDOW", "scope", "ENDPOINT", "window", "PT1M",
                "limit", 15, "version", 1);
        var updated = rest.exchange("/api/admin/rate-limit/policies/" + projectionId, HttpMethod.PUT,
                new HttpEntity<>(update, admin()), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).contains("\"limit\":15");

        var deleted = rest.exchange("/api/admin/rate-limit/policies/" + projectionId, HttpMethod.DELETE,
                new HttpEntity<>(admin()), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var detail = rest.exchange("/api/admin/rate-limit/groups/group-legacy", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(detail.getBody()).contains("\"scope\":\"IP\"").doesNotContain("\"scope\":\"ENDPOINT\"");
    }

    @Test
    void staleVersionReturnsConflictAndKeepsTheStoredPolicy() {
        var create = Map.of(
                "id", "conflict-route", "method", "GET", "path", "/api/conflict",
                "algorithm", "FIXED_WINDOW", "scope", "IP",
                "window", "PT1M", "limit", 10, "enabled", true);
        rest.exchange("/api/admin/rate-limit/policies", HttpMethod.POST,
                new HttpEntity<>(create, admin()), String.class);

        // Version 99 was never issued: the caller is working from a stale or fabricated base.
        var bogus = Map.of(
                "id", "conflict-route", "method", "GET", "path", "/api/conflict",
                "algorithm", "FIXED_WINDOW", "scope", "IP",
                "window", "PT1M", "limit", 999, "enabled", true, "version", 99);
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies/conflict-route",
                HttpMethod.PUT, new HttpEntity<>(bogus, admin()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("version_conflict");

        ResponseEntity<String> current = rest.exchange("/api/admin/rate-limit/policies/conflict-route",
                HttpMethod.GET, new HttpEntity<>(admin()), String.class);
        assertThat(current.getBody()).contains("\"limit\":10");
    }

    @Test
    void invalidPolicyIsRejectedWithFieldProblems() {
        var bad = Map.of(
                "id", "bad-route", "method", "GET", "path", "/api/bad",
                "algorithm", "FIXED_WINDOW", "scope", "IP",
                "window", "PT1M", "enabled", true);
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.POST,
                new HttpEntity<>(bad, admin()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("policy_invalid").contains("limit");
    }

    @Test
    void implementedAlgorithmsSaveAndBecomeEnforceable() {
        var tokenBucket = Map.of(
                "id", "tb-route", "method", "GET", "path", "/api/tb",
                "algorithm", "TOKEN_BUCKET", "scope", "IP",
                "capacity", 100, "refillInterval", "PT10S", "enabled", true);
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.POST,
                new HttpEntity<>(tokenBucket, admin()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"algorithmImplemented\":true");
    }

    @Test
    void auditTrailRecordsTheAdministratorNotACredential() {
        var create = Map.of(
                "id", "audit-route", "method", "GET", "path", "/api/audit",
                "algorithm", "FIXED_WINDOW", "scope", "IP",
                "window", "PT1M", "limit", 10, "enabled", true);
        rest.exchange("/api/admin/rate-limit/policies", HttpMethod.POST,
                new HttpEntity<>(create, admin()), String.class);

        ResponseEntity<String> audit = rest.exchange("/api/admin/rate-limit/audit", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.getBody()).contains("admin-test").contains("CREATE").contains("audit-route");
        assertThat(audit.getBody())
                .as("the secret must never reach an audit record")
                .doesNotContain("admin-test-secret");
    }

    @Test
    void adminRoutesAreNotThemselvesRateLimited() {
        // A throttled administrator could not undo the policy that is throttling it.
        for (int i = 0; i < 30; i++) {
            ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                    new HttpEntity<>(admin()), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void policyReadinessIsReportedPerAlgorithm() {
        // The list tells the UI which algorithms actually enforce, so a dropdown cannot imply support
        // that does not exist.
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(response.getBody()).contains("\"algorithmImplemented\":true");
    }

    @Test
    void capabilitiesDescribeOnlyWhatIsEnforced() {
        ResponseEntity<String> response = rest.exchange("/api/admin/rate-limit/capabilities", HttpMethod.GET,
                new HttpEntity<>(admin()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"FIXED_WINDOW\",\"implemented\":true");
        assertThat(response.getBody()).contains("\"name\":\"TOKEN_BUCKET\",\"implemented\":true");
        assertThat(response.getBody()).contains("single-redis");
    }

    @Test
    void capabilitiesAreAdminOnly() {
        ResponseEntity<String> anonymous = rest.getForEntity("/api/admin/rate-limit/capabilities",
                String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> denied = rest.exchange("/api/admin/rate-limit/capabilities", HttpMethod.GET,
                new HttpEntity<>(demoUser()), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void concurrencyPermitReleasesOnError() {
        var policy = Map.of(
                "id", "work-conc", "method", "GET", "path", "/api/work",
                "algorithm", "CONCURRENCY_LIMIT", "scope", "GLOBAL",
                "maxConcurrent", 1, "leaseDuration", "PT30S", "enabled", true);
        ResponseEntity<String> saved = rest.exchange("/api/admin/rate-limit/policies",
                HttpMethod.POST, new HttpEntity<>(policy, admin()), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The only permit is held, then the request fails: the permit must still be released.
        ResponseEntity<String> failed = rest.exchange("/api/work?ms=10&fail=true", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ResponseEntity<String> after = rest.exchange("/api/work?ms=10", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertThat(after.getStatusCode()).as("permit released despite the error").isEqualTo(HttpStatus.OK);
    }
}
