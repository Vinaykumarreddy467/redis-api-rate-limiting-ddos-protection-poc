package com.example.ratelimit.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlobalScopeRules(
        List<ScopeRule> rules,
        FailureMode onRedisError,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        List<String> projectionIds) {

    public GlobalScopeRules {
        rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        projectionIds = projectionIds != null ? new ArrayList<>(projectionIds) : new ArrayList<>();
        for (ScopeRule rule : rules) {
            if (rule.scope() != Scope.APPLICATION && rule.scope() != Scope.GLOBAL) {
                throw new PolicyValidationException(List.of(
                        "global scope rules can only contain APPLICATION or GLOBAL scopes"));
            }
        }
    }
}
