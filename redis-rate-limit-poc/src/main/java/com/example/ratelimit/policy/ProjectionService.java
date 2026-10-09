package com.example.ratelimit.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class ProjectionService {

    public String projectionId(String groupId, String endpointId, Scope scope) {
        String input = groupId + ":" + endpointId + ":" + scope.name();
        return "p-" + sha256Hex(input).substring(0, 56);
    }

    public String globalProjectionId(Scope scope) {
        String input = "global:" + scope.name();
        return "p-" + sha256Hex(input).substring(0, 56);
    }

    public List<PolicyDocument> projectGroup(PolicyGroup group) {
        var projections = new ArrayList<PolicyDocument>();
        for (var endpoint : group.endpoints()) {
            for (var rule : endpoint.scopeRules()) {
                projections.add(projectEndpointRule(group, endpoint, rule));
            }
        }
        return projections;
    }

    public List<PolicyDocument> projectGlobalRules(GlobalScopeRules globalRules) {
        var projections = new ArrayList<PolicyDocument>();
        if (globalRules == null || globalRules.rules() == null) {
            return projections;
        }
        for (var rule : globalRules.rules()) {
            projections.add(projectGlobalRule(globalRules, rule));
        }
        return projections;
    }

    public List<String> projectionIdsForGroup(PolicyGroup group) {
        var ids = new ArrayList<String>();
        for (var endpoint : group.endpoints()) {
            for (var rule : endpoint.scopeRules()) {
                ids.add(projectionId(group.id(), endpoint.id(), rule.scope()));
            }
        }
        return ids;
    }

    public List<String> projectionIdsForGlobalRules(GlobalScopeRules globalRules) {
        var ids = new ArrayList<String>();
        if (globalRules == null || globalRules.rules() == null) {
            return ids;
        }
        for (var rule : globalRules.rules()) {
            ids.add(globalProjectionId(rule.scope()));
        }
        return ids;
    }

    private PolicyDocument projectEndpointRule(PolicyGroup group, EndpointRule endpoint, ScopeRule rule) {
        String projId = projectionId(group.id(), endpoint.id(), rule.scope());
        return PolicyDocument.builder(projId)
                .name(group.name() + " - " + endpoint.displayName())
                .route(endpoint.method(), endpoint.path())
                .algorithm(rule.algorithm())
                .scope(rule.scope())
                .window(rule.window(), rule.limit())
                .bucket(rule.capacity(), rule.refillInterval(), rule.cost())
                .leaky(rule.drainRate(), rule.queueCapacity())
                .concurrency(rule.maxConcurrent(), rule.leaseDuration())
                .enabled(group.enabled())
                .onRedisError(rule.onRedisError() != null ? rule.onRedisError() : group.onRedisError())
                .version(group.version())
                .timestamps(group.createdAt(), group.updatedAt())
                .updatedBy(group.updatedBy())
                .build();
    }

    private PolicyDocument projectGlobalRule(GlobalScopeRules globalRules, ScopeRule rule) {
        String projId = globalProjectionId(rule.scope());
        return PolicyDocument.builder(projId)
                .name("Global " + rule.scope().name())
                .route("ANY", null)
                .algorithm(rule.algorithm())
                .scope(rule.scope())
                .window(rule.window(), rule.limit())
                .bucket(rule.capacity(), rule.refillInterval(), rule.cost())
                .leaky(rule.drainRate(), rule.queueCapacity())
                .concurrency(rule.maxConcurrent(), rule.leaseDuration())
                .enabled(true)
                .onRedisError(rule.onRedisError() != null ? rule.onRedisError() : globalRules.onRedisError())
                .version(globalRules.version())
                .timestamps(globalRules.createdAt(), globalRules.updatedAt())
                .updatedBy(globalRules.updatedBy())
                .build();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
