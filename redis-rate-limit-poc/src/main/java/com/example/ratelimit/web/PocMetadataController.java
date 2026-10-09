package com.example.ratelimit.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.config.RateLimitProperties.Identity;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicyGroup;
import com.example.ratelimit.policy.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only metadata for the RateGuard console: which policies are currently enforced and how this
 * instance would behave if Redis were unreachable.
 *
 * <p>Reads the administrator-managed policies from shared Redis, so the console shows live edits
 * rather than the startup seed. When the managed store is empty or unreachable it falls back to the
 * bound {@code rate-limit.*} configuration — the same baseline the enforcement path falls back to —
 * and says so in {@code source}. Nothing is mutable over HTTP, and no Redis key, client identity
 * or credential is exposed.
 */
@RestController
@RequestMapping("/api/poc")
public class PocMetadataController {

    private static final Logger log = LoggerFactory.getLogger(PocMetadataController.class);

    private final ManagedPolicyStore store;
    private final RateLimitProperties properties;

    public PocMetadataController(ManagedPolicyStore store, RateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping("/policies")
    public Map<String, Object> policies() {
        List<PolicyDocument> managed = List.of();
        boolean live = false;
        Map<String, PolicyGroup> groupMap = new HashMap<>();
        try {
            managed = store.findAll();
            live = !managed.isEmpty();
            for (var group : store.findAllGroups()) {
                groupMap.put(group.id(), group);
            }
        } catch (RuntimeException e) {
            log.warn("managed policy lookup failed for the console ({}); showing the configuration baseline",
                    e.getClass().getSimpleName());
        }
        List<Map<String, Object>> policies = new ArrayList<>();
        String source;
        if (live) {
            for (PolicyDocument policy : managed) {
                policies.add(row(policy, groupMap));
            }
            source = "managed policy store (Redis)";
        } else {
            for (Policy policy : properties.getPolicies()) {
                policies.add(legacyRow(policy));
            }
            source = "rate-limit.policies (application.yml)";
        }
        return Map.of(
                "source", source,
                "editable", true,
                "limiterEnabled", properties.isEnabled(),
                "defaultRedisFailureMode", properties.getOnRedisError().name(),
                "policyCount", policies.size(),
                "policies", policies);
    }

    /** One managed policy, in the shape the console table binds to. Nulls stay null: a token bucket
     * has no window, a concurrency policy has no request limit, and the table renders that honestly
     * instead of inventing a number. */
    private Map<String, Object> row(PolicyDocument policy, Map<String, PolicyGroup> groupMap) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", policy.id());
        row.put("method", policy.method());
        row.put("path", policy.path());
        row.put("algorithm", policy.algorithm() == null ? null : policy.algorithm().name());
        row.put("scope", policy.scope() == null ? null : policy.scope().name());
        row.put("parameterSummary", safeSummary(policy));
        row.put("enabled", policy.enabled());
        row.put("version", policy.version());
        row.put("limit", policy.limit());
        row.put("windowSeconds",
                policy.window() == null ? null : policy.window().toSeconds());
        row.put("identity", identityLabel(policy));
        FailureMode failureMode = policy.onRedisError() != null
                ? policy.onRedisError()
                : properties.getOnRedisError();
        row.put("redisFailureMode", failureMode.name());
        row.put("redisFailureModeLabel", switch (failureMode) {
            case FAIL_OPEN -> "Fail open";
            case FAIL_CLOSED -> "Fail closed";
        });
        // Additive group metadata for Phase 1 policy groups
        String groupId = null;
        String endpointId = null;
        String groupName = null;
        for (var group : groupMap.values()) {
            if (group.projectionIds().contains(policy.id())) {
                groupId = group.id();
                groupName = group.name();
                for (var endpoint : group.endpoints()) {
                    for (var rule : endpoint.scopeRules()) {
                        if (policy.id().equals(new com.example.ratelimit.policy.ProjectionService()
                                .projectionId(group.id(), endpoint.id(), rule.scope()))) {
                            endpointId = endpoint.id();
                            break;
                        }
                    }
                }
                break;
            }
        }
        row.put("groupId", groupId);
        row.put("endpointId", endpointId);
        row.put("groupName", groupName);
        return row;
    }

    /** The identity token the enforcement path charges, so the console names what is limited. */
    private static String identityLabel(PolicyDocument policy) {
        if (policy.scope() == Scope.USER) {
            return "USER";
        }
        if (policy.scope() == Scope.GLOBAL) {
            return "GLOBAL";
        }
        if (policy.scope() == Scope.APPLICATION) {
            return "APPLICATION";
        }
        return "IP";
    }

    private static String safeSummary(PolicyDocument policy) {
        try {
            return policy.describeParameters();
        } catch (RuntimeException e) {
            return "incomplete";
        }
    }

    private Map<String, Object> legacyRow(Policy policy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", policy.id());
        row.put("method", policy.method());
        row.put("path", policy.path());
        row.put("algorithm", "FIXED_WINDOW");
        row.put("scope", policy.identity() == RateLimitProperties.Identity.USER ? "USER" : "IP");
        row.put("parameterSummary",
                policy.limit() + " per " + policy.window().toSeconds() + "s");
        row.put("enabled", true);
        row.put("version", 1);
        row.put("limit", policy.limit());
        row.put("windowSeconds", policy.window().toSeconds());
        row.put("identity", policy.identity().name());
        FailureMode failureMode = policy.failureMode(properties.getOnRedisError());
        row.put("redisFailureMode", failureMode.name());
        row.put("redisFailureModeLabel", switch (failureMode) {
            case FAIL_OPEN -> "Fail open";
            case FAIL_CLOSED -> "Fail closed";
        });
        return row;
    }
}
