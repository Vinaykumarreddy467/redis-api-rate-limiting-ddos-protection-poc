package com.example.ratelimit.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.policy.Algorithm;
import com.example.ratelimit.policy.EndpointRule;
import com.example.ratelimit.policy.ExemptionDocument;
import com.example.ratelimit.policy.GlobalScopeRules;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyNotFoundException;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreException;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreUnavailableException;
import com.example.ratelimit.policy.PolicyConflictException;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicyGroup;
import com.example.ratelimit.policy.PolicyValidationException;
import com.example.ratelimit.policy.PolicySeeder;
import com.example.ratelimit.policy.ProjectionService;
import com.example.ratelimit.policy.Scope;
import com.example.ratelimit.policy.ScopeRule;
import com.example.ratelimit.admin.AdminDtos.EnabledRequest;
import com.example.ratelimit.admin.AdminDtos.ErrorResponse;
import com.example.ratelimit.admin.AdminDtos.PolicyRequest;
import com.example.ratelimit.admin.AdminDtos.PolicyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration API for rate-limit policies.
 *
 * <p>Authorization is enforced in {@code SecurityConfig}: every path under {@code /api/admin/**}
 * requires {@code ROLE_ADMIN}. The demo {@code alice}/{@code bob} accounts hold {@code ROLE_USER} and
 * are refused here. This controller does not check roles itself, so there is one place to audit.
 *
 * <p>Mutations use {@link Authentication#getName()} as the audit actor. That is a principal name, never
 * a credential, and it is the only identity detail recorded.
 */
@RestController
@RequestMapping("/api/admin/rate-limit")
public class PolicyAdminController {

    private static final Logger log = LoggerFactory.getLogger(PolicyAdminController.class);

    private final ManagedPolicyStore store;
    private final PolicySeeder seeder;
    private final com.example.ratelimit.policy.ExemptionStore exemptions;
    private final ProjectionService projectionService;
    private final com.example.ratelimit.policy.PolicyGroupValidator validator;

    public PolicyAdminController(ManagedPolicyStore store, PolicySeeder seeder,
            com.example.ratelimit.policy.ExemptionStore exemptions, ProjectionService projectionService,
            com.example.ratelimit.policy.PolicyGroupValidator validator) {
        this.store = store;
        this.seeder = seeder;
        this.exemptions = exemptions;
        this.projectionService = projectionService;
        this.validator = validator;
    }

    @GetMapping("/policies")
    public List<PolicyResponse> list() {
        var byId = new java.util.TreeMap<String, PolicyResponse>();
        for (var policy : store.findAll()) byId.put(policy.id(), PolicyResponse.from(policy));
        var groups = store.findAllGroups();
        for (var group : groups) {
            for (var policy : projectionService.projectGroup(group)) {
                byId.put(policy.id(), PolicyResponse.from(policy, PolicyResponse.SOURCE_GROUP));
            }
        }
        store.findGlobalRules().ifPresent(rules -> projectionService.projectGlobalRules(rules)
                .forEach(policy -> byId.put(policy.id(),
                        PolicyResponse.from(policy, PolicyResponse.SOURCE_GLOBAL))));
        return List.copyOf(byId.values());
    }

    /**
     * What this build can actually enforce. The admin UI binds its algorithm dropdown and scope
     * selectors to this response, so an option is selectable only when the backend enforces it.
     * An enum constant elsewhere in the codebase is not enforcement.
     */
    @GetMapping("/capabilities")
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                List.of(
                        new AlgorithmCapability("FIXED_WINDOW", true,
                                "Counter per epoch-aligned window. Preserves the original POC semantics.",
                                List.of(
                                        new Parameter("limit", true, "Requests allowed per window."),
                                        new Parameter("window", true,
                                                "Window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("SLIDING_WINDOW", true,
                                "Exact rolling window: at most limit events in any trailing window. State is one "
                                        + "sorted set per identity, trimmed on every decision and expired when idle; "
                                        + "memory is bounded by limit entries per identity.",
                                List.of(
                                        new Parameter("limit", true, "Events allowed per trailing window."),
                                        new Parameter("window", true,
                                                "Trailing window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("SLIDING_WINDOW_COUNTER", true,
                                "APPROXIMATE rolling window: current count plus the previous window count weighted "
                                        + "by how far the current window has advanced. Cheap, but an estimate, not a "
                                        + "guarantee.",
                                List.of(
                                        new Parameter("limit", true, "Approximate ceiling per window."),
                                        new Parameter("window", true,
                                                "Window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("TOKEN_BUCKET", true,
                                "Burst of capacity, then a sustained rate of capacity per refillInterval. "
                                        + "Capacity 100 with a 10s refill sustains 10/sec after the burst; "
                                        + "it does not mean 100/min. Cost defaults to 1.",
                                List.of(
                                        new Parameter("capacity", true, "Burst size in tokens."),
                                        new Parameter("refillInterval", true,
                                                "Time to refill an empty bucket, ISO-8601 duration of at least 1s."),
                                        new Parameter("cost", false,
                                                "Tokens per request, at least 1. Defaults to 1."))),
                        new AlgorithmCapability("LEAKY_BUCKET", true,
                                "POLICING, not queued shaping: requests beyond queueCapacity are rejected, never "
                                        + "queued. The water level drains continuously at drainRate requests per "
                                        + "second; Retry-After reflects when enough capacity is expected.",
                                List.of(
                                        new Parameter("drainRate", true, "Requests drained per second."),
                                        new Parameter("queueCapacity", true, "Burst depth before overflow rejects."))),
                        new AlgorithmCapability("CONCURRENCY_LIMIT", true,
                                "Caps in-flight requests across all instances with Redis leases. Permits release when "
                                        + "the request completes; crashed holders are reclaimed when the lease "
                                        + "expires. leaseDuration is the maximum request duration: a request running "
                                        + "longer may lose its permit.",
                                List.of(
                                        new Parameter("maxConcurrent", true, "Permits shared across instances."),
                                        new Parameter("leaseDuration", true,
                                                "Maximum request duration, ISO-8601 duration of at least 1s.")))),
                List.of(
                        new ScopeCapability("ENDPOINT", true, "Method plus route template."),
                        new ScopeCapability("IP", true, "Canonical client IP behind trusted-proxy gating."),
                        new ScopeCapability("USER", true,
                                "Authenticated principal; falls back to IP when unauthenticated. A USER policy on a "
                                        + "wide route pattern shares one quota across endpoints."),
                        new ScopeCapability("GLOBAL", true,
                                "One quota shared by every route and identity on every instance."),
                        new ScopeCapability("APPLICATION", true,
                                "One quota shared across all in-scope API routes, regardless of IP or user.")),
                "AND: every applicable enabled policy must allow. One atomic batch inspects all "
                        + "counters before charging any, so a denial charges nothing anywhere.",
                "single-redis: batch scripts span keys without hash tags; Redis Cluster is unsupported.");
    }

    public record AlgorithmCapability(String name, boolean implemented, String note,
            List<Parameter> parameters) {
    }

    public record Parameter(String name, boolean required, String help) {
    }

    public record ScopeCapability(String name, boolean implemented, String note) {
    }

    public record CapabilitiesResponse(
            List<AlgorithmCapability> algorithms,
            List<ScopeCapability> scopes,
            String composition,
            String topology) {
    }

    @GetMapping("/policies/{id}")
    public PolicyResponse get(@PathVariable String id) {
        var groupOpt = store.findGroup(id);
        if (groupOpt.isPresent()) {
            var group = groupOpt.get();
            if (group.endpoints().size() == 1 && group.endpoints().get(0).scopeRules().size() == 1) {
                var endpoint = group.endpoints().get(0);
                var rule = endpoint.scopeRules().get(0);
                var doc = projectionService.projectGroup(group).get(0);
                return PolicyResponse.from(doc);
            }
            throw new PolicyValidationException(List.of(
                    "group '" + id + "' has multiple endpoints/rules; use /groups/" + id + " instead"));
        }
        var target = findProjectionTarget(id);
        if (target != null) {
            return PolicyResponse.from(projectionService.projectGroup(target.group()).stream()
                    .filter(policy -> policy.id().equals(id)).findFirst()
                    .orElseThrow(() -> new PolicyNotFoundException(id)));
        }
        if (findGlobalProjectionScope(id) != null) {
            return PolicyResponse.from(projectionService.projectGlobalRules(
                    store.findGlobalRules().orElseThrow(() -> new PolicyNotFoundException(id))).stream()
                    .filter(policy -> policy.id().equals(id)).findFirst()
                    .orElseThrow(() -> new PolicyNotFoundException(id)));
        }
        return PolicyResponse.from(store.find(id).orElseThrow(() -> new PolicyNotFoundException(id)));
    }

    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> create(@RequestBody PolicyRequest request, Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var groupOpt = store.findGroup(request.id() != null ? request.id() : UUID.randomUUID().toString());
        if (groupOpt.isPresent()) {
            throw new PolicyValidationException(List.of("group with id '" + request.id() + "' already exists"));
        }
        var scope = request.scope() != null ? request.scope() : Scope.ENDPOINT;
        if (scope == Scope.GLOBAL || scope == Scope.APPLICATION) {
            var rule = new ScopeRule(scope, request.algorithm(), request.window(), request.limit(),
                    request.capacity(), request.refillInterval(), request.cost(), request.drainRate(),
                    request.queueCapacity(), request.maxConcurrent(), request.leaseDuration(),
                    request.onRedisError());
            var globalRules = store.findGlobalRules().orElse(null);
            if (globalRules != null && globalRules.rules().stream().anyMatch(r -> r.scope() == scope)) {
                throw new PolicyValidationException(List.of("a global rule already exists for " + scope));
            }
            var updatedGlobal = globalRules != null ? new GlobalScopeRules(
                    new ArrayList<>(globalRules.rules()) {{
                        add(rule);
                    }},
                    globalRules.onRedisError(), globalRules.version() + 1,
                    globalRules.createdAt(), Instant.now(), actor(auth), List.of())
                    : new GlobalScopeRules(List.of(rule), request.onRedisError(), 1, Instant.now(), Instant.now(), actor(auth), List.of());
            var groups = store.findAllGroups();
            validator.validateGlobalRules(updatedGlobal, groups);
            var projections = projectionService.projectGlobalRules(updatedGlobal);
            store.saveGlobalRules(updatedGlobal, globalRules, actor(auth), projections, expectedEpoch);
            var proj = projections.stream().filter(p -> p.scope() == scope).findFirst().orElse(projections.get(0));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(etag(updatedGlobal.version()))
                    .body(PolicyResponse.from(proj));
        }
        var groupId = request.id() != null ? request.id() : UUID.randomUUID().toString();
        var endpointId = UUID.randomUUID().toString();
        var rule = new ScopeRule(scope, request.algorithm(), request.window(), request.limit(),
                request.capacity(), request.refillInterval(), request.cost(), request.drainRate(),
                request.queueCapacity(), request.maxConcurrent(), request.leaseDuration(),
                request.onRedisError());
        var endpoint = new EndpointRule(endpointId, request.method(), request.path(),
                request.name() != null ? request.name() : request.path(), true, false, List.of(rule));
        var group = new PolicyGroup(groupId, request.name() != null ? request.name() : groupId, true,
                List.of(endpoint), request.onRedisError(), 1, Instant.now(), Instant.now(), actor(auth), List.of());
        var globalRules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
        validator.validateGroup(group, globalRules);
        var projections = projectionService.projectGroup(group);
        var saved = store.saveGroup(group, null, actor(auth), projections, expectedEpoch);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(saved.version()))
                .body(PolicyResponse.from(projections.get(0)));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<PolicyResponse> update(@PathVariable String id, @RequestBody PolicyRequest request,
            Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var groupOpt = store.findGroup(id);
        if (groupOpt.isPresent()) {
            var group = groupOpt.get();
            if (group.endpoints().size() == 1 && group.endpoints().get(0).scopeRules().size() == 1) {
                var endpoint = group.endpoints().get(0);
                var rule = endpoint.scopeRules().get(0);
                var updatedRule = new ScopeRule(rule.scope(), request.algorithm() != null ? request.algorithm() : rule.algorithm(),
                        request.window() != null ? request.window() : rule.window(),
                        request.limit() != null ? request.limit() : rule.limit(),
                        request.capacity() != null ? request.capacity() : rule.capacity(),
                        request.refillInterval() != null ? request.refillInterval() : rule.refillInterval(),
                        request.cost() != null ? request.cost() : rule.cost(),
                        request.drainRate() != null ? request.drainRate() : rule.drainRate(),
                        request.queueCapacity() != null ? request.queueCapacity() : rule.queueCapacity(),
                        request.maxConcurrent() != null ? request.maxConcurrent() : rule.maxConcurrent(),
                        request.leaseDuration() != null ? request.leaseDuration() : rule.leaseDuration(),
                        request.onRedisError() != null ? request.onRedisError() : rule.onRedisError());
                var updatedEndpoint = new EndpointRule(endpoint.id(),
                        request.method() != null ? request.method() : endpoint.method(),
                        request.path() != null ? request.path() : endpoint.path(),
                        request.name() != null ? request.name() : endpoint.displayName(),
                        endpoint.repeatable(), endpoint.exempt(), List.of(updatedRule));
                var updatedGroup = new PolicyGroup(group.id(),
                        request.name() != null ? request.name() : group.name(),
                        request.enabled() != null ? request.enabled() : group.enabled(),
                        List.of(updatedEndpoint),
                        request.onRedisError() != null ? request.onRedisError() : group.onRedisError(),
                        request.version() != null ? request.version() + 1 : group.version() + 1, group.createdAt(), Instant.now(), actor(auth), group.projectionIds());
                var projections = projectionService.projectGroup(updatedGroup);
                var globalRules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
                validator.validateGroup(updatedGroup, globalRules);
                var saved = store.saveGroup(updatedGroup, group, actor(auth), projections, expectedEpoch);
                return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(projections.get(0)));
            }
            throw new PolicyValidationException(List.of(
                    "group '" + id + "' has multiple endpoints/rules; use /groups/" + id + " instead"));
        }
        var projectionTarget = findProjectionTarget(id);
        if (projectionTarget != null) {
            if (request.scope() != null && request.scope() != projectionTarget.scope()) {
                throw new PolicyValidationException(List.of("scope cannot be changed through a projected policy; edit the group instead"));
            }
            var group = projectionTarget.group();
            if (request.version() == null || request.version() != group.version()) {
                throw new PolicyConflictException(group.version(), request.version() == null ? -1 : request.version());
            }
            var endpoints = new ArrayList<EndpointRule>();
            for (var endpoint : group.endpoints()) {
                if (!endpoint.id().equals(projectionTarget.endpoint().id())) {
                    endpoints.add(endpoint);
                    continue;
                }
                var rules = endpoint.scopeRules().stream().map(rule -> rule.scope() == projectionTarget.scope()
                        ? mergeRule(rule, request) : rule).toList();
                endpoints.add(new EndpointRule(endpoint.id(),
                        request.method() != null ? request.method() : endpoint.method(),
                        request.path() != null ? request.path() : endpoint.path(),
                        request.name() != null ? request.name() : endpoint.displayName(),
                        endpoint.repeatable(), endpoint.exempt(), rules));
            }
            var updatedGroup = new PolicyGroup(group.id(), group.name(),
                    request.enabled() != null ? request.enabled() : group.enabled(), endpoints,
                    request.onRedisError() != null ? request.onRedisError() : group.onRedisError(),
                    group.version() + 1, group.createdAt(), Instant.now(), actor(auth), group.projectionIds());
            var globalRules = store.findGlobalRules().orElse(null);
            validator.validateGroup(updatedGroup, globalRules);
            var projections = projectionService.projectGroup(updatedGroup);
            var saved = store.saveGroup(updatedGroup, group, actor(auth), projections, expectedEpoch);
            var updatedProjection = projections.stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new PolicyNotFoundException(id));
            return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(updatedProjection));
        }
        Scope globalScope = findGlobalProjectionScope(id);
        if (globalScope != null) {
            var current = store.findGlobalRules().orElseThrow(() -> new PolicyNotFoundException(id));
            if (request.version() == null || request.version() != current.version()) {
                throw new PolicyConflictException(current.version(), request.version() == null ? -1 : request.version());
            }
            var rules = current.rules().stream().map(rule -> rule.scope() == globalScope
                    ? mergeRule(rule, request) : rule).toList();
            var updated = new GlobalScopeRules(rules,
                    request.onRedisError() != null ? request.onRedisError() : current.onRedisError(),
                    current.version() + 1, current.createdAt(), Instant.now(), actor(auth), current.projectionIds());
            validator.validateGlobalRules(updated, store.findAllGroups());
            var docs = projectionService.projectGlobalRules(updated);
            var saved = store.saveGlobalRules(updated, current, actor(auth), docs, expectedEpoch);
            return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(docs.stream()
                    .filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new PolicyNotFoundException(id))));
        }
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        if (request.id() != null && !id.equals(request.id())) {
            throw new PolicyValidationException(List.of(
                    "id in the body (" + request.id() + ") does not match the path (" + id + ")"));
        }
        if (request.version() == null) {
            throw new PolicyValidationException(List.of(
                    "version is required on update; send the version you read"));
        }
        PolicyDocument document = toDocument(request, existing, actor(auth));
        PolicyDocument saved = store.save(document, existing, actor(auth));
        return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(saved));
    }

    @PatchMapping("/policies/{id}/enabled")
    public ResponseEntity<PolicyResponse> setEnabled(@PathVariable String id,
            @RequestBody EnabledRequest request, Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var groupOpt = store.findGroup(id);
        if (groupOpt.isPresent()) {
            var group = groupOpt.get();
            if (group.endpoints().size() == 1 && group.endpoints().get(0).scopeRules().size() == 1) {
                var endpoint = group.endpoints().get(0);
                var rule = endpoint.scopeRules().get(0);
                var updatedGroup = new PolicyGroup(group.id(), group.name(),
                        request.enabled() != null ? request.enabled() : group.enabled(),
                        group.endpoints(), group.onRedisError(),
                        request.version() != null ? request.version() + 1 : group.version() + 1,
                        group.createdAt(), Instant.now(), actor(auth), group.projectionIds());
                var projections = projectionService.projectGroup(updatedGroup);
                var saved = store.saveGroup(updatedGroup, group, actor(auth), projections, expectedEpoch);
                return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(projections.get(0)));
            }
            throw new PolicyValidationException(List.of(
                    "group '" + id + "' has multiple endpoints/rules; use /groups/" + id + " instead"));
        }
        var projectionTarget = findProjectionTarget(id);
        if (projectionTarget != null) {
            var group = projectionTarget.group();
            if (request.enabled() == null) {
                throw new PolicyValidationException(List.of("enabled is required and must be true or false"));
            }
            if (request.version() == null || request.version() != group.version()) {
                throw new PolicyConflictException(group.version(), request.version() == null ? -1 : request.version());
            }
            var updatedGroup = new PolicyGroup(group.id(), group.name(), request.enabled(), group.endpoints(),
                    group.onRedisError(), group.version() + 1, group.createdAt(), Instant.now(), actor(auth),
                    group.projectionIds());
            var projections = projectionService.projectGroup(updatedGroup);
            var saved = store.saveGroup(updatedGroup, group, actor(auth), projections, expectedEpoch);
            var updatedProjection = projections.stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new PolicyNotFoundException(id));
            return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(updatedProjection));
        }
        if (findGlobalProjectionScope(id) != null) {
            if (!Boolean.TRUE.equals(request.enabled())) {
                throw new PolicyValidationException(List.of("global rules do not support per-rule enabled changes"));
            }
            var rules = store.findGlobalRules().orElseThrow(() -> new PolicyNotFoundException(id));
            return ResponseEntity.ok().eTag(etag(rules.version())).body(PolicyResponse.from(
                    projectionService.projectGlobalRules(rules).stream().filter(p -> p.id().equals(id))
                            .findFirst().orElseThrow(() -> new PolicyNotFoundException(id))));
        }
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        if (request.enabled() == null) {
            throw new PolicyValidationException(List.of("enabled is required and must be true or false"));
        }
        long nextVersion = request.version() != null ? request.version() + 1 : existing.version() + 1;
        PolicyDocument updated = PolicyDocument.builder(existing.id())
                .name(existing.name())
                .route(existing.method(), existing.path())
                .algorithm(existing.algorithm())
                .scope(existing.scope())
                .window(existing.window(), existing.limit())
                .bucket(existing.capacity(), existing.refillInterval(), existing.cost())
                .leaky(existing.drainRate(), existing.queueCapacity())
                .concurrency(existing.maxConcurrent(), existing.leaseDuration())
                .enabled(request.enabled())
                .onRedisError(existing.onRedisError())
                .version(nextVersion)
                .timestamps(existing.createdAt(), Instant.now())
                .updatedBy(actor(auth))
                .build();
        PolicyDocument saved = store.save(updated, existing, actor(auth));
        return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(saved));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var groupOpt = store.findGroup(id);
        if (groupOpt.isPresent()) {
            var group = groupOpt.get();
            if (group.endpoints().size() == 1 && group.endpoints().get(0).scopeRules().size() == 1) {
                store.deleteGroup(id, actor(auth), expectedEpoch);
                return ResponseEntity.noContent().build();
            }
            throw new PolicyValidationException(List.of(
                    "group '" + id + "' has multiple endpoints/rules; use /groups/" + id + " instead"));
        }
        var projectionTarget = findProjectionTarget(id);
        if (projectionTarget != null) {
            var group = projectionTarget.group();
            var endpoints = new ArrayList<EndpointRule>();
            for (var endpoint : group.endpoints()) {
                if (!endpoint.id().equals(projectionTarget.endpoint().id())) {
                    endpoints.add(endpoint);
                    continue;
                }
                endpoints.add(new EndpointRule(endpoint.id(), endpoint.method(), endpoint.path(),
                        endpoint.displayName(), endpoint.repeatable(), endpoint.exempt(),
                        endpoint.scopeRules().stream().filter(r -> r.scope() != projectionTarget.scope()).toList()));
            }
            var updatedGroup = new PolicyGroup(group.id(), group.name(), group.enabled(), endpoints,
                    group.onRedisError(), group.version() + 1, group.createdAt(), Instant.now(), actor(auth),
                    group.projectionIds());
            var saved = store.saveGroup(updatedGroup, group, actor(auth), projectionService.projectGroup(updatedGroup),
                    expectedEpoch);
            return ResponseEntity.noContent().build();
        }
        Scope globalScope = findGlobalProjectionScope(id);
        if (globalScope != null) {
            var current = store.findGlobalRules().orElseThrow(() -> new PolicyNotFoundException(id));
            var updated = new GlobalScopeRules(current.rules().stream().filter(r -> r.scope() != globalScope).toList(),
                    current.onRedisError(), current.version() + 1, current.createdAt(), Instant.now(), actor(auth),
                    current.projectionIds());
            validator.validateGlobalRules(updated, store.findAllGroups());
            store.saveGlobalRules(updated, current, actor(auth), projectionService.projectGlobalRules(updated),
                    expectedEpoch);
            return ResponseEntity.noContent().build();
        }
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        store.delete(id, actor(auth), existing.version());
        return ResponseEntity.noContent().build();
    }

    // --- group and global-rules endpoints ---

    @GetMapping("/groups")
    public List<GroupResponse> listGroups() {
        return store.findAllGroups().stream().map(this::toGroupResponse).toList();
    }

    @GetMapping("/groups/{id}")
    public GroupResponse getGroup(@PathVariable String id) {
        var group = store.findGroup(id).orElseThrow(() -> new PolicyNotFoundException(id));
        return toGroupResponse(group);
    }

    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private String generateGroupId(String name) {
        var slug = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 55) {
            slug = slug.substring(0, 55).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "group";
        }
        var candidate = slug;
        for (int n = 2; store.findGroup(candidate).isPresent(); n++) {
            candidate = slug + "-" + n;
        }
        return candidate;
    }

    /**
     * Turns the request's endpoints into rules, enforcing id ownership and uniqueness.
     *
     * <p>An endpoint id is generated by the server and never changes: on update an id must belong to the
     * group being edited, and an endpoint without one is new. Renaming an id would change the projected
     * policy id and orphan its live counters. A method and path may appear only once across all groups,
     * otherwise a single request is charged by two rules. Duplicates that already exist in stored data
     * are tolerated unless the caller is the one adding or moving them.
     */
    private List<EndpointRule> buildEndpoints(List<EndpointRequest> requested, PolicyGroup existing,
            String groupId) {
        var problems = new ArrayList<String>();
        var endpoints = new ArrayList<EndpointRule>();
        var knownIds = new java.util.HashSet<String>();
        var previous = new java.util.HashMap<String, EndpointRule>();
        if (existing != null) {
            existing.endpoints().forEach(e -> {
                knownIds.add(e.id());
                previous.put(e.id(), e);
            });
        }
        var usedIds = new java.util.HashSet<String>();
        for (var ep : requested == null ? List.<EndpointRequest>of() : requested) {
            String id = ep.id();
            boolean isNew = id == null || id.isBlank();
            if (!isNew && existing != null && !knownIds.contains(id)) {
                problems.add("endpoint id '" + id + "' does not belong to this group; ids are generated by the "
                        + "server and cannot be changed (omit the id to add a new endpoint)");
                continue;
            }
            if (!isNew && existing == null && !ID_PATTERN.matcher(id).matches()) {
                problems.add("endpoint id '" + id + "' is invalid: use lowercase letters, digits and hyphens");
                continue;
            }
            if (isNew) {
                do {
                    id = "ep-" + UUID.randomUUID().toString().substring(0, 8);
                } while (knownIds.contains(id) || usedIds.contains(id));
            } else if (usedIds.contains(id)) {
                problems.add("endpoint id '" + id + "' is used twice in this group");
                continue;
            }
            usedIds.add(id);
            var rules = new ArrayList<ScopeRule>();
            for (var rule : ep.scopeRules() == null ? List.<ScopeRuleRequest>of() : ep.scopeRules()) {
                rules.add(toScopeRule(rule));
            }
            if (ep.path() == null || ep.path().isBlank()) {
                problems.add("endpoint path is required");
                continue;
            }
            endpoints.add(new EndpointRule(id, ep.method(), ep.path().trim(), ep.displayName(),
                    ep.repeatable(), ep.exempt(), rules));
        }
        // A route is only a problem when this request adds it or moves an endpoint onto it, so groups that
        // already hold duplicates (older data) can still be edited for unrelated reasons.
        var others = new ArrayList<EndpointRule>();
        var ownerName = new java.util.HashMap<String, String>();
        for (var other : store.findAllGroups()) {
            if (!other.id().equals(groupId)) {
                other.endpoints().forEach(e -> {
                    others.add(e);
                    ownerName.put(e.id(), other.name());
                });
            }
        }
        for (int i = 0; i < endpoints.size(); i++) {
            var endpoint = endpoints.get(i);
            var before = previous.get(endpoint.id());
            if (before != null && routeKey(before).equals(routeKey(endpoint))) {
                continue;
            }
            for (int j = 0; j < endpoints.size(); j++) {
                if (j != i && overlaps(endpoint, endpoints.get(j))) {
                    problems.add(routeKey(endpoint) + " overlaps another endpoint in this group");
                    break;
                }
            }
            for (var other : others) {
                if (overlaps(endpoint, other)) {
                    problems.add(routeKey(endpoint) + " is already managed by group '"
                            + ownerName.get(other.id()) + "'");
                    break;
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
        return endpoints;
    }

    /** Same path (ignoring a trailing slash) and the same method, where ANY matches every method. */
    private static boolean overlaps(EndpointRule a, EndpointRule b) {
        if (!normalizePath(a.path()).equals(normalizePath(b.path()))) {
            return false;
        }
        var ma = a.method() == null ? "" : a.method().toUpperCase(java.util.Locale.ROOT);
        var mb = b.method() == null ? "" : b.method().toUpperCase(java.util.Locale.ROOT);
        return ma.equals(mb) || ma.equals("ANY") || mb.equals("ANY");
    }

    private static String normalizePath(String path) {
        var p = path == null ? "" : path.trim();
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static String routeKey(EndpointRule endpoint) {
        return (endpoint.method() == null ? "" : endpoint.method().toUpperCase(java.util.Locale.ROOT))
                + " " + normalizePath(endpoint.path());
    }

    @PostMapping("/groups")
    public ResponseEntity<GroupResponse> createGroup(@RequestBody GroupRequest request, Authentication auth) {
        long expectedEpoch = store.getEpoch();
        // The server owns ids. A caller-supplied group id is still honoured (scripts and tests pin one),
        // but the UI sends none and gets a readable slug of the name.
        var groupId = request.id() != null && !request.id().isBlank() ? request.id() : generateGroupId(request.name());
        if (!ID_PATTERN.matcher(groupId).matches()) {
            throw new PolicyValidationException(List.of("group id '" + groupId
                    + "' is invalid: use lowercase letters, digits and hyphens (max 63 characters)"));
        }
        if (store.findGroup(groupId).isPresent()) {
            throw new PolicyValidationException(List.of("group with id '" + groupId + "' already exists"));
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new PolicyValidationException(List.of("name is required"));
        }
        var endpoints = buildEndpoints(request.endpoints(), null, groupId);
        var group = new PolicyGroup(groupId, request.name() != null ? request.name() : groupId,
                request.enabled() != null ? request.enabled() : true, endpoints,
                request.onRedisError(), 1, Instant.now(), Instant.now(), actor(auth), List.of());
        var globalRules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
        validator.validateGroup(group, globalRules);
        var projections = projectionService.projectGroup(group);
        var saved = store.saveGroup(group, null, actor(auth), projections, expectedEpoch);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(saved.version())).body(toGroupResponse(saved));
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<GroupResponse> updateGroup(@PathVariable String id, @RequestBody GroupRequest request,
            Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var group = store.findGroup(id).orElseThrow(() -> new PolicyNotFoundException(id));
        if (request.version() == null) {
            throw new PolicyValidationException(List.of("version is required on update"));
        }
        var endpoints = buildEndpoints(request.endpoints(), group, id);
        var updated = new PolicyGroup(id, request.name() != null ? request.name() : group.name(),
                request.enabled() != null ? request.enabled() : group.enabled(), endpoints,
                request.onRedisError() != null ? request.onRedisError() : group.onRedisError(),
                request.version() + 1, group.createdAt(), Instant.now(), actor(auth), group.projectionIds());
        var globalRules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
        validator.validateGroup(updated, globalRules);
        var projections = projectionService.projectGroup(updated);
        var saved = store.saveGroup(updated, group, actor(auth), projections, expectedEpoch);
        return ResponseEntity.ok().eTag(etag(saved.version())).body(toGroupResponse(saved));
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable String id, Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var group = store.findGroup(id).orElseThrow(() -> new PolicyNotFoundException(id));
        store.deleteGroup(id, actor(auth), expectedEpoch);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{groupId}/endpoints/{endpointId}")
    public ResponseEntity<GroupResponse> deleteEndpoint(@PathVariable String groupId, @PathVariable String endpointId,
            Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var group = store.findGroup(groupId).orElseThrow(() -> new PolicyNotFoundException(groupId));
        var endpoints = new ArrayList<EndpointRule>(group.endpoints().stream()
                .filter(e -> !e.id().equals(endpointId))
                .toList());
        if (endpoints.size() == group.endpoints().size()) {
            throw new PolicyNotFoundException(endpointId);
        }
        var updated = new PolicyGroup(group.id(), group.name(), group.enabled(), endpoints,
                group.onRedisError(), group.version() + 1, group.createdAt(), Instant.now(),
                actor(auth), group.projectionIds());
        var globalRules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
        validator.validateGroup(updated, globalRules);
        var projections = projectionService.projectGroup(updated);
        var saved = store.saveGroup(updated, group, actor(auth), projections, expectedEpoch);
        return ResponseEntity.ok().eTag(etag(saved.version())).body(toGroupResponse(saved));
    }

    @PostMapping("/groups/{id}/repair")
    public ResponseEntity<RepairResponse> repairGroup(@PathVariable String id, Authentication auth) {
        var counts = store.repairProjections(id, actor(auth));
        return ResponseEntity.ok(new RepairResponse(id, counts.written(), counts.deleted(), Instant.now()));
    }

    @GetMapping("/global-rules")
    public GlobalRulesResponse getGlobalRules() {
        var rules = store.findGlobalRules().orElse(new GlobalScopeRules(List.of(), FailureMode.FAIL_OPEN, 0, Instant.now(), Instant.now(), "system", List.of()));
        return new GlobalRulesResponse(rules.rules().stream().map(r -> new ScopeRuleDto(r.scope().name(),
                r.algorithm().name(), r.window() != null ? r.window().toString() : null,
                r.limit(), r.capacity(), r.refillInterval() != null ? r.refillInterval().toString() : null,
                r.cost(), r.drainRate(), r.queueCapacity(), r.maxConcurrent(),
                r.leaseDuration() != null ? r.leaseDuration().toString() : null,
                r.onRedisError() != null ? r.onRedisError().name() : null)).toList(),
                rules.onRedisError(), rules.version(),
                rules.createdAt(), rules.updatedAt(), rules.updatedBy());
    }

    @PutMapping("/global-rules")
    public ResponseEntity<GlobalRulesResponse> updateGlobalRules(@RequestBody GlobalRulesRequest request,
            Authentication auth) {
        long expectedEpoch = store.getEpoch();
        var existing = store.findGlobalRules().orElse(null);
        if (request.version() == null) {
            throw new PolicyValidationException(List.of("version is required on update"));
        }
        if (request.rules() == null) {
            throw new PolicyValidationException(List.of("rules is required"));
        }
        var rules = new ArrayList<ScopeRule>();
        for (var rule : request.rules()) {
            rules.add(toScopeRule(rule));
        }
        var updated = existing != null ? new GlobalScopeRules(rules,
                request.onRedisError() != null ? request.onRedisError() : existing.onRedisError(),
                request.version() + 1, existing.createdAt(), Instant.now(), actor(auth), List.of())
                : new GlobalScopeRules(rules,
                request.onRedisError(), 1, Instant.now(), Instant.now(), actor(auth), List.of());
        var groups = store.findAllGroups();
        validator.validateGlobalRules(updated, groups);
        var projections = projectionService.projectGlobalRules(updated);
        var saved = store.saveGlobalRules(updated, existing, actor(auth), projections, expectedEpoch);
        return ResponseEntity.ok().eTag(etag(saved.version()))
                .body(new GlobalRulesResponse(saved.rules().stream().map(r -> new ScopeRuleDto(r.scope().name(),
                        r.algorithm().name(), r.window() != null ? r.window().toString() : null,
                        r.limit(), r.capacity(), r.refillInterval() != null ? r.refillInterval().toString() : null,
                        r.cost(), r.drainRate(), r.queueCapacity(), r.maxConcurrent(),
                        r.leaseDuration() != null ? r.leaseDuration().toString() : null,
                        r.onRedisError() != null ? r.onRedisError().name() : null)).toList(),
                        saved.onRedisError(), saved.version(),
                        saved.createdAt(), saved.updatedAt(), saved.updatedBy()));
    }

    private GroupResponse toGroupResponse(PolicyGroup group) {
        var endpoints = group.endpoints().stream().map(ep -> {
            var rules = ep.scopeRules().stream().map(r -> new ScopeRuleDto(r.scope().name(),
                    r.algorithm().name(), r.window() != null ? r.window().toString() : null,
                    r.limit(), r.capacity(),
                    r.refillInterval() != null ? r.refillInterval().toString() : null,
                    r.cost(), r.drainRate(), r.queueCapacity(), r.maxConcurrent(),
                    r.leaseDuration() != null ? r.leaseDuration().toString() : null,
                    r.onRedisError() != null ? r.onRedisError().name() : null)).toList();
            return new EndpointRuleDto(ep.id(), ep.method(), ep.path(), ep.displayName(),
                    ep.repeatable(), ep.exempt(), rules);
        }).toList();
        return new GroupResponse(group.id(), group.name(), group.enabled(), endpoints,
                group.onRedisError(), group.version(), group.createdAt(), group.updatedAt(),
                group.updatedBy());
    }

    private ProjectionTarget findProjectionTarget(String projectionId) {
        for (var group : store.findAllGroups()) {
            for (var endpoint : group.endpoints()) {
                for (var rule : endpoint.scopeRules()) {
                    if (projectionService.projectionId(group.id(), endpoint.id(), rule.scope()).equals(projectionId)) {
                        return new ProjectionTarget(group, endpoint, rule.scope());
                    }
                }
            }
        }
        return null;
    }

    private Scope findGlobalProjectionScope(String projectionId) {
        return store.findGlobalRules().stream().flatMap(rules -> rules.rules().stream())
                .map(ScopeRule::scope)
                .filter(scope -> projectionService.globalProjectionId(scope).equals(projectionId))
                .findFirst().orElse(null);
    }

    private static ScopeRule mergeRule(ScopeRule rule, PolicyRequest request) {
        return new ScopeRule(rule.scope(), pick(request.algorithm(), rule.algorithm()),
                pick(request.window(), rule.window()), pick(request.limit(), rule.limit()),
                pick(request.capacity(), rule.capacity()), pick(request.refillInterval(), rule.refillInterval()),
                pick(request.cost(), rule.cost()), pick(request.drainRate(), rule.drainRate()),
                pick(request.queueCapacity(), rule.queueCapacity()), pick(request.maxConcurrent(), rule.maxConcurrent()),
                pick(request.leaseDuration(), rule.leaseDuration()), pick(request.onRedisError(), rule.onRedisError()));
    }

    /**
     * Builds a rule from a request body. A missing or unknown scope, algorithm or failure mode, and a
     * duration that is not ISO-8601 (for example "60s" instead of "PT60S"), are reported together as a
     * validation error instead of escaping as an unhandled exception and an HTTP 500.
     */
    private static ScopeRule toScopeRule(ScopeRuleRequest rule) {
        var problems = new ArrayList<String>();
        Scope scope = parseEnum(Scope.class, rule.scope(), "scope", true, problems);
        Algorithm algorithm = parseEnum(Algorithm.class, rule.algorithm(), "algorithm", true, problems);
        FailureMode failureMode = parseEnum(FailureMode.class, rule.onRedisError(), "onRedisError", false, problems);
        var window = parseDuration(rule.window(), "window", problems);
        var refillInterval = parseDuration(rule.refillInterval(), "refillInterval", problems);
        var leaseDuration = parseDuration(rule.leaseDuration(), "leaseDuration", problems);
        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
        return new ScopeRule(scope, algorithm, window, rule.limit(), rule.capacity(), refillInterval,
                rule.cost(), rule.drainRate(), rule.queueCapacity(), rule.maxConcurrent(), leaseDuration,
                failureMode);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field, boolean required,
            List<String> problems) {
        if (value == null || value.isBlank()) {
            if (required) problems.add(field + " is required");
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            problems.add(field + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
            return null;
        }
    }

    private static java.time.Duration parseDuration(String value, String field, List<String> problems) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.Duration.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            problems.add(field + " must be an ISO-8601 duration such as PT1M, PT30S or PT1H");
            return null;
        }
    }

    private record ProjectionTarget(PolicyGroup group, EndpointRule endpoint, Scope scope) { }

    public record GroupRequest(String id, String name, Boolean enabled, List<EndpointRequest> endpoints,
            FailureMode onRedisError, Long version) {
    }

    public record EndpointRequest(String id, String method, String path, String displayName,
            boolean repeatable, boolean exempt, List<ScopeRuleRequest> scopeRules) {
    }

    public record ScopeRuleRequest(String scope, String algorithm, String window, Integer limit,
            Integer capacity, String refillInterval, Integer cost, Integer drainRate,
            Integer queueCapacity, Integer maxConcurrent, String leaseDuration,
            String onRedisError) {
    }

    public record GroupResponse(String id, String name, boolean enabled, List<EndpointRuleDto> endpoints,
            FailureMode onRedisError, long version, Instant createdAt, Instant updatedAt, String updatedBy) {
    }

    public record EndpointRuleDto(String id, String method, String path, String displayName,
            boolean repeatable, boolean exempt, List<ScopeRuleDto> scopeRules) {
    }

    public record ScopeRuleDto(String scope, String algorithm, String window, Integer limit,
            Integer capacity, String refillInterval, Integer cost, Integer drainRate,
            Integer queueCapacity, Integer maxConcurrent, String leaseDuration,
            String onRedisError) {
    }

    public record GlobalRulesRequest(List<ScopeRuleRequest> rules, FailureMode onRedisError, Long version) {
    }

    public record GlobalRulesResponse(List<ScopeRuleDto> rules, FailureMode onRedisError, long version,
            Instant createdAt, Instant updatedAt, String updatedBy) {
    }

    public record RepairResponse(String groupId, int written, int deleted, Instant at) {
    }

    @GetMapping("/audit")
    public List<AuditEntryResponse> audit(@RequestParam(defaultValue = "50") int limit) {
        return store.audit(limit).stream()
                .map(e -> new AuditEntryResponse(e.at(), e.actor(), e.policyId(), e.operation(),
                        e.resultingVersion(), e.changedFields()))
                .toList();
    }

    /**
     * Intentional local reset: clears policies, audit and the seeded marker so the next start reseeds
     * from {@code application.yml}. Guarded by ROLE_ADMIN like every other mutation, and never called
     * automatically.
     */
    @PostMapping("/policies/reset")
    public ResponseEntity<ResetResponse> reset(Authentication auth) {
        int removed = store.reset(actor(auth));
        int reseeded = seeder.seed(actor(auth));
        return ResponseEntity.ok(new ResetResponse(removed, reseeded, Instant.now()));
    }

    public record AuditEntryResponse(Instant at, String actor, String policyId, String operation,
            long resultingVersion, List<String> changedFields) {
    }

    public record ResetResponse(int removed, int reseeded, Instant at) {
    }

    /**
     * Projects the request onto a document.
     *
     * <p>On create, {@code version} starts at 1 and {@code existing} is null. Omitted fields therefore
     * stay null rather than being read from {@code existing}: {@link PolicyDocument#validate()} then
     * reports exactly what is missing, instead of the save failing with a null dereference.
     *
     * <p>On update the client's version is carried through so the store's atomic compare-and-set is what
     * actually detects a concurrent edit.
     */
    private static PolicyDocument toDocument(PolicyRequest request, PolicyDocument existing, String actor) {
        Instant now = Instant.now();
        Algorithm algorithm = pick(request.algorithm(), existing == null ? null : existing.algorithm());
        // Only parameters that belong to the chosen algorithm survive. Without this, switching an
        // existing policy's algorithm kept the old algorithm's values alongside the new ones.
        boolean windowed = algorithm == Algorithm.FIXED_WINDOW || algorithm == Algorithm.SLIDING_WINDOW
                || algorithm == Algorithm.SLIDING_WINDOW_COUNTER;
        boolean tokenBucket = algorithm == Algorithm.TOKEN_BUCKET;
        boolean leakyBucket = algorithm == Algorithm.LEAKY_BUCKET;
        boolean concurrency = algorithm == Algorithm.CONCURRENCY_LIMIT;
        var b = PolicyDocument.builder(pick(request.id(), existing == null ? null : existing.id()))
                .name(pick(request.name(), existing == null ? null : existing.name()))
                .route(pick(request.method(), existing == null ? null : existing.method()),
                        pick(request.path(), existing == null ? null : existing.path()))
                .algorithm(algorithm)
                .scope(pick(request.scope(), existing == null ? null : existing.scope()))
                .window(windowed ? pick(request.window(), existing == null ? null : existing.window()) : null,
                        windowed ? pick(request.limit(), existing == null ? null : existing.limit()) : null)
                .bucket(tokenBucket ? pick(request.capacity(), existing == null ? null : existing.capacity()) : null,
                        tokenBucket ? pick(request.refillInterval(),
                                existing == null ? null : existing.refillInterval()) : null,
                        tokenBucket ? pick(request.cost(), existing == null ? null : existing.cost()) : null)
                .leaky(leakyBucket ? pick(request.drainRate(), existing == null ? null : existing.drainRate()) : null,
                        leakyBucket ? pick(request.queueCapacity(),
                                existing == null ? null : existing.queueCapacity()) : null)
                .concurrency(concurrency ? pick(request.maxConcurrent(),
                                existing == null ? null : existing.maxConcurrent()) : null,
                        concurrency ? pick(request.leaseDuration(),
                                existing == null ? null : existing.leaseDuration()) : null)
                .onRedisError(pick(request.onRedisError(), existing == null ? null : existing.onRedisError()))
                // The request carries the version the client read; the stored version becomes base + 1.
                // The store's Lua then requires stored == base, which is the optimistic-concurrency check.
                .version(request.version() != null ? request.version() + 1 : 1)
                .timestamps(existing != null ? existing.createdAt() : now, now)
                .updatedBy(actor);

        if (request.enabled() != null) {
            b.enabled(request.enabled());
        } else if (existing != null) {
            b.enabled(existing.enabled());
        }
        if (request.name() == null && existing != null) {
            b.name(existing.name());
        }
        return b.build();
    }

    /** Request value when supplied, otherwise the stored value, otherwise null. */
    private static <T> T pick(T requested, T stored) {
        return requested != null ? requested : stored;
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Principal name only. No credential, no API key, no header value. */
    private static String actor(Authentication auth) {
        return auth == null ? "unknown" : auth.getName();
    }

    // --- error mapping. Each handler returns a safe message; nothing leaks internals to the client.

    @ExceptionHandler(PolicyValidationException.class)
    public ResponseEntity<ErrorResponse> onInvalid(PolicyValidationException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.of("policy_invalid", "policy is invalid", e.problems()));
    }

    @ExceptionHandler(PolicyConflictException.class)
    public ResponseEntity<ErrorResponse> onConflict(PolicyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("version_conflict",
                "stored version is " + e.storedVersion() + "; reload the policy and re-apply your change"));
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> onMissing(PolicyNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("policy_not_found", e.getMessage()));
    }

    @ExceptionHandler(PolicyStoreUnavailableException.class)
    public ResponseEntity<ErrorResponse> onUnavailable(PolicyStoreUnavailableException e) {
        log.warn("policy store unavailable while serving an admin request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse.of(
                "store_unavailable", "the policy store is temporarily unavailable"));
    }

    /** Unparseable JSON or a wrong type (for example "enabled": "yes") is the caller's mistake, not a 500. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("malformed_request",
                "the request body is not valid JSON or has a value of the wrong type"));
    }

    @ExceptionHandler(PolicyStoreException.class)
    public ResponseEntity<ErrorResponse> onStoreFailure(PolicyStoreException e) {
        log.error("policy store failure: {}", e.getMessage());
return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("store_error", "the policy store could not complete the request"));
    }

    // --- exemptions ---

    @GetMapping("/exemptions")
    public List<ExemptionResponse> listExemptions() {
        return exemptions.findAll().stream().map(ExemptionResponse::from).toList();
    }

    @PostMapping("/exemptions")
    public ResponseEntity<ExemptionResponse> createExemption(@RequestBody ExemptionRequest request, Authentication auth) {
        var doc = ExemptionDocument.builder(request.id())
                .name(request.name() != null ? request.name() : request.id())
                .route(request.method(), request.path())
                .enabled(true)
                .version(1)
                .updatedBy(actor(auth))
                .build();
        var saved = exemptions.save(doc, null, actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(ExemptionResponse.from(saved));
    }

    @DeleteMapping("/exemptions/{id}")
    public ResponseEntity<Void> deleteExemption(@PathVariable String id, Authentication auth) {
        exemptions.delete(id, actor(auth));
        return ResponseEntity.noContent().build();
    }

    public record ExemptionRequest(String id, String name, String method, String path) {
    }

    public record ExemptionResponse(String id, String name, String method, String path, boolean enabled,
            long version, Instant createdAt, Instant updatedAt, String updatedBy) {
        static ExemptionResponse from(com.example.ratelimit.policy.ExemptionDocument d) {
            return new ExemptionResponse(d.id(), d.name(), d.method(), d.path(), d.enabled(), d.version(),
                    d.createdAt(), d.updatedAt(), d.updatedBy());
        }
    }
}
