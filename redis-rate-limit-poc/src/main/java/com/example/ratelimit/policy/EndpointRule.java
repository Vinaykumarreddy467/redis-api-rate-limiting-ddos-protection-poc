package com.example.ratelimit.policy;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EndpointRule(
        String id,
        String method,
        String path,
        String displayName,
        boolean repeatable,
        boolean exempt,
        List<ScopeRule> scopeRules) {

    public EndpointRule {
        if (id == null || id.isBlank()) {
            throw new PolicyValidationException(List.of("endpoint id is required"));
        }
        if (method == null || !method.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY")) {
            throw new PolicyValidationException(List.of("endpoint method must be an HTTP verb or ANY"));
        }
        if (path == null || !path.startsWith("/")) {
            throw new PolicyValidationException(List.of("endpoint path must start with '/'"));
        }
        scopeRules = scopeRules != null ? new ArrayList<>(scopeRules) : new ArrayList<>();
        for (ScopeRule rule : scopeRules) {
            if (rule.scope() == Scope.APPLICATION || rule.scope() == Scope.GLOBAL) {
                throw new PolicyValidationException(List.of(
                        "endpoint scope rules cannot contain APPLICATION or GLOBAL; those belong to the global-rules resource"));
            }
        }
    }
}
