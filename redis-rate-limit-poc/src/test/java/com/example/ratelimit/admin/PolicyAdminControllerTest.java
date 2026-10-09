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
    @SuppressWarnings("unchecked")
    void switchingAlgorithmDropsParametersOfTheOldAlgorithm() {
        var url = "/api/admin/rate-limit/policies/products-read";
        var current = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(admin()), Map.class).getBody();
        long version = ((Number) current.get("version")).longValue();

        var toBucket = new java.util.HashMap<String, Object>(Map.of(
                "algorithm", "TOKEN_BUCKET", "capacity", 100, "refillInterval", "PT10S",
                "version", version));
        var bucket = rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(toBucket, admin()), Map.class);
        assertThat(bucket.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bucket.getBody().get("limit")).as("window fields do not belong to a token bucket").isNull();
        assertThat(bucket.getBody().get("window")).isNull();

        var toWindow = new java.util.HashMap<String, Object>(Map.of(
                "algorithm", "FIXED_WINDOW", "limit", 30, "window", "PT1M",
                "version", ((Number) bucket.getBody().get("version")).longValue()));
        var window = rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(toWindow, admin()), Map.class);
        assertThat(window.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(window.getBody().get("limit")).isEqualTo(30);
        assertThat(window.getBody().get("capacity")).as("stale bucket value must not survive").isNull();
        assertThat(window.getBody().get("refillInterval")).isNull();
        assertThat(window.getBody().get("cost")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedRuleValuesAreRejectedAsValidationErrorsNotServerErrors() {
        for (var bad : java.util.List.of(
                Map.<String, Object>of("scope", "IP", "algorithm", "FIXED_WINDOW", "window", "60s", "limit", 5),
                Map.<String, Object>of("scope", "IP", "algorithm", "NOT_AN_ALGORITHM", "window", "PT1M", "limit", 5),
                Map.<String, Object>of("scope", "BOGUS", "algorithm", "FIXED_WINDOW", "window", "PT1M", "limit", 5),
                Map.<String, Object>of("algorithm", "FIXED_WINDOW", "window", "PT1M", "limit", 5))) {
            var endpoint = Map.of("id", "ep-bad", "method", "GET", "path", "/api/bad",
                    "displayName", "Bad", "repeatable", true, "exempt", false,
                    "scopeRules", java.util.List.of(bad));
            var group = Map.of("id", "bad-group", "name", "Bad", "enabled", true,
                    "endpoints", java.util.List.of(endpoint));
            var response = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                    new HttpEntity<>(group, admin()), Map.class);
            assertThat(response.getStatusCode()).as("rule %s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((java.util.List<String>) response.getBody().get("problems")).isNotEmpty();
        }
    }

    private static Map<String, Object> idTestEndpoint(String id, String path) {
        var ep = new java.util.HashMap<String, Object>();
        if (id != null) {
            ep.put("id", id);
        }
        ep.put("method", "GET");
        ep.put("path", path);
        ep.put("displayName", "Endpoint " + path);
        ep.put("repeatable", true);
        ep.put("exempt", false);
        ep.put("scopeRules", java.util.List.of(Map.of("scope", "IP", "algorithm", "FIXED_WINDOW",
                "window", "PT1M", "limit", 5)));
        return ep;
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverGeneratesReadableGroupIdAndEndpointIdsAndKeepsThemOnUpdate() {
        var group = Map.<String, Object>of("name", "Id Test Orders", "enabled", true,
                "endpoints", java.util.List.of(idTestEndpoint(null, "/api/idtest-a")));
        var created = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(group, admin()), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = created.getBody();
        assertThat(body.get("id")).isEqualTo("id-test-orders");
        var endpointId = (String) ((Map<String, Object>) ((java.util.List<?>) body.get("endpoints")).get(0)).get("id");
        assertThat(endpointId).startsWith("ep-");

        // A second group with the same name gets a numbered id instead of colliding.
        var second = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(Map.<String, Object>of("name", "Id Test Orders", "enabled", true,
                        "endpoints", java.util.List.of(idTestEndpoint(null, "/api/idtest-b"))), admin()), Map.class);
        assertThat(second.getBody().get("id")).isEqualTo("id-test-orders-2");

        // Update keeps the id; an unknown id (a rename) is rejected; an id-less endpoint is new.
        var keep = Map.<String, Object>of("version", 1, "endpoints", java.util.List.of(
                idTestEndpoint(endpointId, "/api/idtest-a"), idTestEndpoint(null, "/api/idtest-c")));
        var ok = rest.exchange("/api/admin/rate-limit/groups/id-test-orders", HttpMethod.PUT,
                new HttpEntity<>(keep, admin()), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) ok.getBody().get("endpoints")).hasSize(2);

        var rename = Map.<String, Object>of("version", 2, "endpoints", java.util.List.of(
                idTestEndpoint("my-own-id", "/api/idtest-a")));
        var rejected = rest.exchange("/api/admin/rate-limit/groups/id-test-orders", HttpMethod.PUT,
                new HttpEntity<>(rename, admin()), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody().toString()).contains("cannot be changed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupWithNoEndpointsCanBeReadBackAndAnUnnamedGroupIsRejected() {
        var empty = Map.<String, Object>of("name", "Empty Group", "enabled", true, "endpoints", java.util.List.of());
        var created = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(empty, admin()), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Reading by id used to fail with a 500 because an empty projection list was stored as {}.
        var read = rest.exchange("/api/admin/rate-limit/groups/empty-group", HttpMethod.GET,
                new HttpEntity<>(admin()), Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ...which also broke creating another group with the same name (slug uniqueness lookup).
        var again = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(empty, admin()), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("id")).isEqualTo("empty-group-2");

        var unnamed = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(Map.of("enabled", true), admin()), Map.class);
        assertThat(unnamed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unnamed.getBody().toString()).contains("name is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedBodiesAreClientErrorsInTheStandardErrorShape() {
        var headers = admin();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        for (var path : java.util.List.of("/groups", "/global-rules")) {
            var wrongType = path.equals("/groups") ? "{\"enabled\":\"yes\"}" : "{\"version\":\"abc\"}";
            for (var body : java.util.List.of("{bad json", wrongType)) {
                var method = path.equals("/groups") ? HttpMethod.POST : HttpMethod.PUT;
                var response = rest.exchange("/api/admin/rate-limit" + path, method,
                        new HttpEntity<>(body, headers), Map.class);
                assertThat(response.getStatusCode()).as("%s %s", path, body).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(response.getBody().get("error")).isEqualTo("malformed_request");
            }
        }
        var nullRules = rest.exchange("/api/admin/rate-limit/global-rules", HttpMethod.PUT,
                new HttpEntity<>("{\"version\":0,\"rules\":null}", headers), Map.class);
        assertThat(nullRules.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullRules.getBody().toString()).contains("rules is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateMethodAndPathIsRejectedWithinAndAcrossGroups() {
        var twice = Map.<String, Object>of("name", "Dup Within", "enabled", true, "endpoints",
                java.util.List.of(idTestEndpoint(null, "/api/dup-test"), idTestEndpoint(null, "/api/dup-test")));
        var within = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(twice, admin()), Map.class);
        assertThat(within.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(within.getBody().toString()).contains("overlaps another endpoint in this group");

        var first = Map.<String, Object>of("name", "Dup First", "enabled", true, "endpoints",
                java.util.List.of(idTestEndpoint(null, "/api/dup-test-2")));
        assertThat(rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(first, admin()), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var other = Map.<String, Object>of("name", "Dup Other", "enabled", true, "endpoints",
                java.util.List.of(idTestEndpoint(null, "/api/dup-test-2")));
        var across = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(other, admin()), Map.class);
        assertThat(across.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(across.getBody().toString()).contains("already managed by group 'Dup First'");

        // A trailing slash or an ANY method is the same route, not a new one.
        var slash = Map.<String, Object>of("name", "Dup Slash", "enabled", true, "endpoints",
                java.util.List.of(idTestEndpoint(null, "/api/dup-test-2/")));
        assertThat(rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(slash, admin()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var any = idTestEndpoint(null, "/api/dup-test-2");
        any.put("method", "ANY");
        assertThat(rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(Map.<String, Object>of("name", "Dup Any", "enabled", true,
                        "endpoints", java.util.List.of(any)), admin()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // A supplied id must be a well-formed slug, so the UI's "draft:" placeholder can never be a real id.
        var badId = Map.<String, Object>of("id", "draft:1", "name", "Bad Id", "enabled", true, "endpoints",
                java.util.List.of(idTestEndpoint(null, "/api/dup-test-3")));
        assertThat(rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(badId, admin()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void policyListLabelsWhereEachPolicyIsOwned() {
        var endpoint = Map.of("id", "ep-owned", "method", "GET", "path", "/api/owned",
                "displayName", "Owned endpoint", "repeatable", true, "exempt", false,
                "scopeRules", java.util.List.of(Map.of("scope", "IP", "algorithm", "FIXED_WINDOW",
                        "window", "PT1M", "limit", 5)));
        var group = Map.of("id", "owner-group", "name", "Owner group", "enabled", true,
                "endpoints", java.util.List.of(endpoint));
        var created = rest.exchange("/api/admin/rate-limit/groups", HttpMethod.POST,
                new HttpEntity<>(group, admin()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var list = rest.exchange("/api/admin/rate-limit/policies", HttpMethod.GET,
                new HttpEntity<>(admin()), java.util.List.class).getBody();
        var rows = (java.util.List<Map<String, Object>>) list;
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("id")).isEqualTo("products-read");
            assertThat(r.get("source")).isEqualTo("POLICY");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat((String) r.get("id")).startsWith("p-");
            assertThat(r.get("name")).isEqualTo("Owner group - Owned endpoint");
            assertThat(r.get("source")).isEqualTo("GROUP");
        });
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
