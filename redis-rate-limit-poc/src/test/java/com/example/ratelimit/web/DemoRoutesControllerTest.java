package com.example.ratelimit.web;

import java.time.Duration;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.policy.Algorithm;
import com.example.ratelimit.policy.ExemptionDocument;
import com.example.ratelimit.policy.ExemptionStore;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicySeeder;
import com.example.ratelimit.policy.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo catalog must offer every managed policy as a selectable target, so the tests write
 * policies through the same store the enforcement path reads and assert each one gets a concrete,
 * sendable target.
 */
@SpringBootTest(properties = {
        "ratelimit.admin.username=pocadmin",
        "ratelimit.admin.password=admin123",
        "ratelimit.admin.raw-password=true"
})
@AutoConfigureMockMvc
@Import(DemoRoutesControllerTest.RedisConfig.class)
class DemoRoutesControllerTest {

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
    MockMvc mvc;

    @Autowired
    ManagedPolicyStore store;

    @Autowired
    ExemptionStore exemptions;

    @Autowired
    PolicySeeder seeder;

    private static final String AUTH = "Authorization";

    private static String basic(String username, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
    }

    @BeforeEach
    void reseedBaseline() {
        store.reset("test-setup");
        seeder.seed("test-setup");
        // Exemptions live in their own store, so they must be cleared explicitly.
        for (var existing : exemptions.findAll()) {
            exemptions.delete(existing.id(), "test-setup");
        }
    }

    private static PolicyDocument fixedWindow(String id, String method, String path, Scope scope, int limit) {
        return PolicyDocument.builder(id)
                .name(id).route(method, path)
                .algorithm(Algorithm.FIXED_WINDOW).scope(scope)
                .window(Duration.ofMinutes(1), limit)
                .version(1).updatedBy("test")
                .build();
    }

    /** Every policy in the store must appear as its own dropdown entry. */
    private void assertEveryPolicyIsATarget() throws Exception {
        var response = mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var storedIds = store.findAll().stream().map(PolicyDocument::id).toList();
        var targetIds = DemoRoutesControllerTest.targetIds(response);

        assertThat(targetIds).containsExactlyInAnyOrderElementsOf(storedIds);
    }

    /** Minimal JSON array extraction, so the assertion does not need a second JSON library. */
    private static java.util.List<String> targetIds(String json) {
        var out = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("\"policyId\"\\s*:\\s*\"([^\"]+)\"");
        var m = matcher.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everyManagedPolicyIsItsOwnDropdownEntry() throws Exception {
        store.save(fixedWindow("extra-products", "GET", "/api/products", Scope.IP, 25), null, "test");
        store.save(fixedWindow("typo-path", "GET", "/api/productz", Scope.IP, 5), null, "test");

        assertEveryPolicyIsATarget();
    }

    @Test
    void entriesCarryIdRouteAlgorithmAndParameters() throws Exception {
        store.save(fixedWindow("solo", "GET", "/api/products", Scope.IP, 25), null, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].configuredMethod").value("GET"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].configuredPath").value("/api/products"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].algorithm").value("FIXED_WINDOW"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].parameterSummary").value("25 per 1 minute"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].testable").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].concretePath").value("/api/products"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'solo')].matchedHandler").value(true));
    }

    @Test
    void duplicatePoliciesOnOneRouteStaySeparateEntriesAndAreReportedAsEnforcedTogether() throws Exception {
        store.save(fixedWindow("extra-products", "GET", "/api/products", Scope.IP, 25), null, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                // Two policies, two entries, even though they share one request target.
                .andExpect(jsonPath("$.targets[?(@.policyId == 'extra-products')].concretePath")
                        .value("/api/products"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'extra-products')].enforcedWith[*].id",
                        org.hamcrest.Matchers.hasItems("extra-products", "products-read")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'products-read')].enforcedWith[*].id",
                        org.hamcrest.Matchers.hasItems("extra-products", "products-read")));
    }

    @Test
    void disabledPoliciesAreListedAsNotEnforcedAndAreNotListedAsEnforcing() throws Exception {
        var stored = store.save(fixedWindow("paused", "GET", "/api/products", Scope.IP, 9), null, "test");
        store.save(PolicyDocument.builder(stored.id())
                .name(stored.name()).route(stored.method(), stored.path())
                .algorithm(stored.algorithm()).scope(stored.scope())
                .window(stored.window(), stored.limit())
                .enabled(false)
                .version(stored.version() + 1).updatedBy("test")
                .build(), stored, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'paused')].enabled").value(false))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'paused')].testable").value(false))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'paused')].reason",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("disabled"))))
                // A disabled policy is not enforced, so it must not appear on a live entry either.
                .andExpect(jsonPath("$.targets[?(@.policyId == 'products-read')].enforcedWith[*]",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("paused"))));
    }

    @Test
    void pathlessGlobalPolicyGetsASafeConcreteTarget() throws Exception {
        store.save(PolicyDocument.builder("app-wide")
                .name("app-wide").route("ANY", null)
                .algorithm(Algorithm.TOKEN_BUCKET).scope(Scope.APPLICATION)
                .bucket(50, Duration.ofSeconds(5), 1)
                .version(1).updatedBy("test")
                .build(), null, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'app-wide')].testable")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'app-wide')].configuredPath")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'app-wide')].concretePath")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("/api/"))))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'app-wide')].note")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("applies to every request"))))
                // It is charged on the sampled request too.
                .andExpect(jsonPath("$.targets[?(@.policyId == 'products-read')].enforcedWith[*].id",
                        org.hamcrest.Matchers.hasItem("app-wide")));
    }

    @Test
    void wildcardPolicyStandsInForARealRouteItCovers() throws Exception {
        store.save(fixedWindow("any-products", "ANY", "/api/*", Scope.IP, 7), null, "test");

        // /api/* covers a registered repeatable route, so that route is the concrete target and no
        // wildcard syntax is ever sent.
        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'any-products')].configuredPath").value("/api/*"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'any-products')].concretePath")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("*"))))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'any-products')].method").value("GET"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'any-products')].matchedHandler").value(true));
    }

    @Test
    void templatedPathIsMadeConcreteInsteadOfSentAsLiteralSyntax() throws Exception {
        store.save(fixedWindow("templated", "GET", "/api/products/{id}", Scope.IP, 4), null, "test");

        var response = mvc.perform(
                get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'templated')].configuredPath")
                        .value("/api/products/{id}"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'templated')].concretePath")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("{"))))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'templated')].testable").value(true))
                .andReturn().getResponse().getContentAsString();

        // The configured value is still reported verbatim; only the request target is made concrete.
        assertThat(response).contains("/api/products/{id}");
    }

    @Test
    void wildcardSyntaxIsNeverTheRequestTarget() {
        assertThat(DemoRouteCatalog.concretize("/api/*")).isEqualTo("/api");
        assertThat(DemoRouteCatalog.concretize("/api/orders/*/items")).isEqualTo("/api/orders");
        assertThat(DemoRouteCatalog.concretize("/api/products/{id}")).isEqualTo("/api/products/1");
        assertThat(DemoRouteCatalog.concretize("/api/products")).isEqualTo("/api/products");
    }

    @Test
    void policiesOnUnregisteredPathsRemainTestableAndReportTheHandlerGap() throws Exception {
        store.save(fixedWindow("typo-path", "GET", "/api/productz", Scope.IP, 5), null, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                // Still selectable and testable: the limiter runs before routing.
                .andExpect(jsonPath("$.targets[?(@.policyId == 'typo-path')].testable").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'typo-path')].matchedHandler").value(false))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'typo-path')].concretePath").value("/api/productz"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'typo-path')].note",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("No handler serves"))));
    }

    @Test
    void unregisteredTargetIsActuallyChargedByTheFilter() throws Exception {
        store.save(fixedWindow("tiny-orphan", "GET", "/api/productz", Scope.IP, 3), null, "test");

        // No handler exists, so downstream routing answers 404 every time. The limiter runs before
        // routing, so the fourth request is still rejected: 404 followed by 429.
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/productz")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/productz")).andExpect(status().isTooManyRequests());
    }

    @Test
    void disposablePocLoginTargetIsListedAndRepeatable() throws Exception {
        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'login-attempt')].enabled").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'login-attempt')].testable").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'login-attempt')].sampleQuery").value("user=demo"))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'login-attempt')].note",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("disposable demo token"))));
    }

    @Test
    void userScopedPolicyIsTestableAndAsksForCredentials() throws Exception {
        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'order-create')].testable").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'order-create')].requiresCredentials").value(true))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'order-create')].method").value("POST"));
    }

    @Test
    void exemptionsAreReportedForTheTargetTheyCover() throws Exception {
        exemptions.save(ExemptionDocument.builder("ex-products")
                .name("ex-products").route("GET", "/api/products")
                .enabled(true).version(1).updatedBy("test").build(), null, "test");

        mvc.perform(get("/api/admin/rate-limit/demo-routes").header(AUTH, basic("pocadmin", "admin123")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'products-read')].exemptions[*]",
                        org.hamcrest.Matchers.hasItem("ex-products")))
                .andExpect(jsonPath("$.targets[?(@.policyId == 'order-create')].exemptions[*]",
                        org.hamcrest.Matchers.empty()));
    }

    @Test
    void catalogRequiresAdminCredentials() throws Exception {
        mvc.perform(get("/api/admin/rate-limit/demo-routes"))
                .andExpect(status().isUnauthorized());
    }
}
