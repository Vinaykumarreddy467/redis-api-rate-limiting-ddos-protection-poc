package com.example.ratelimit.policy;

import java.time.Duration;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScopeRule(
        Scope scope,
        Algorithm algorithm,
        Duration window,
        Integer limit,
        Integer capacity,
        Duration refillInterval,
        Integer cost,
        Integer drainRate,
        Integer queueCapacity,
        Integer maxConcurrent,
        Duration leaseDuration,
        FailureMode onRedisError) {

    public ScopeRule {
        if (scope == null) {
            throw new PolicyValidationException(List.of("scope is required"));
        }
        if (algorithm == null) {
            throw new PolicyValidationException(List.of("algorithm is required"));
        }
    }

    public double normalizedRps() {
        return switch (algorithm) {
            case FIXED_WINDOW, SLIDING_WINDOW, SLIDING_WINDOW_COUNTER -> {
                if (window == null || limit == null) yield Double.MAX_VALUE;
                yield (double) limit / window.getSeconds();
            }
            case TOKEN_BUCKET -> {
                if (refillInterval == null || capacity == null) yield Double.MAX_VALUE;
                yield (double) capacity / refillInterval.getSeconds();
            }
            case LEAKY_BUCKET -> {
                if (drainRate == null) yield Double.MAX_VALUE;
                yield drainRate;
            }
            case CONCURRENCY_LIMIT -> Double.NaN;
        };
    }

    public boolean isComparable() {
        return algorithm != Algorithm.CONCURRENCY_LIMIT;
    }
}
