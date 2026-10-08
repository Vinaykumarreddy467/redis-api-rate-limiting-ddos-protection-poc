package com.example.ratelimit.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyGroup(
        String id,
        String name,
        boolean enabled,
        List<EndpointRule> endpoints,
        FailureMode onRedisError,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        List<String> projectionIds) {

    public PolicyGroup {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new PolicyValidationException(List.of("group id must match [a-z0-9][a-z0-9-]{0,62}"));
        }
        endpoints = endpoints != null ? new ArrayList<>(endpoints) : new ArrayList<>();
        projectionIds = projectionIds != null ? new ArrayList<>(projectionIds) : new ArrayList<>();
    }
}
