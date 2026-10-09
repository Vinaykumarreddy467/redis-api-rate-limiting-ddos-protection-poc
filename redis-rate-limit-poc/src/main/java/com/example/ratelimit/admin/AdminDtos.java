package com.example.ratelimit.admin;

import java.time.Instant;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.policy.Algorithm;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.Scope;

/**
 * Request and response shapes for the administration API.
 *
 * <p>Kept separate from {@link PolicyDocument} so the persisted shape can evolve without silently
 * changing the public contract, and so a response can carry server-derived fields (version, audit
 * actor) that a client must not be able to set.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** Body for create and update. {@code version} is required on update and must be the stored one. */
    public record PolicyRequest(
            String id,
            String name,
            String method,
            String path,
            Algorithm algorithm,
            Scope scope,
            java.time.Duration window,
            Integer limit,
            Integer capacity,
            java.time.Duration refillInterval,
            Integer cost,
            Integer drainRate,
            Integer queueCapacity,
            Integer maxConcurrent,
            java.time.Duration leaseDuration,
            Boolean enabled,
            FailureMode onRedisError,
            Long version) {
    }

    /** Policy as returned to an administrator, including server-owned fields. */
    public record PolicyResponse(
            String id,
            String name,
            String method,
            String path,
            Algorithm algorithm,
            boolean algorithmImplemented,
            Scope scope,
            java.time.Duration window,
            Integer limit,
            Integer capacity,
            java.time.Duration refillInterval,
            Integer cost,
            Integer drainRate,
            Integer queueCapacity,
            Integer maxConcurrent,
            java.time.Duration leaseDuration,
            boolean enabled,
            FailureMode onRedisError,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String updatedBy,
            String parameterSummary,
            String source) {

        /** A standalone policy document, as opposed to a rule projected from a group or global scope. */
        public static final String SOURCE_POLICY = "POLICY";
        public static final String SOURCE_GROUP = "GROUP";
        public static final String SOURCE_GLOBAL = "GLOBAL";

        public static PolicyResponse from(PolicyDocument p) {
            return new PolicyResponse(p.id(), p.name(), p.method(), p.path(), p.algorithm(),
                    p.algorithm() != null && p.algorithm().isImplemented(), p.scope(),
                    p.window(), p.limit(),
                    p.capacity(), p.refillInterval(), p.cost(), p.drainRate(), p.queueCapacity(),
                    p.maxConcurrent(), p.leaseDuration(), p.enabled(), p.onRedisError(), p.version(),
                    p.createdAt(), p.updatedAt(), p.updatedBy(), safeSummary(p), SOURCE_POLICY);
        }

        /** Same policy, labelled with where it is owned so the console can separate groups from legacy rows. */
        public static PolicyResponse from(PolicyDocument p, String source) {
            var r = from(p);
            return new PolicyResponse(r.id(), r.name(), r.method(), r.path(), r.algorithm(),
                    r.algorithmImplemented(), r.scope(), r.window(), r.limit(), r.capacity(),
                    r.refillInterval(), r.cost(), r.drainRate(), r.queueCapacity(), r.maxConcurrent(),
                    r.leaseDuration(), r.enabled(), r.onRedisError(), r.version(), r.createdAt(),
                    r.updatedAt(), r.updatedBy(), r.parameterSummary(), source);
        }

        private static String safeSummary(PolicyDocument p) {
            try {
                return p.describeParameters();
            } catch (RuntimeException e) {
                return "incomplete";
            }
        }
    }

    /** Body for the enable/disable toggle. */
    public record EnabledRequest(Boolean enabled, Long version) {
    }

    public record AuditResponse(List<?> entries) {
    }

    /** Uniform error body. Never carries a credential, a raw API key or a Redis connection detail. */
    public record ErrorResponse(String error, String message, List<String> problems, Instant at) {

        public static ErrorResponse of(String error, String message) {
            return new ErrorResponse(error, message, List.of(), Instant.now());
        }

        public static ErrorResponse of(String error, String message, List<String> problems) {
            return new ErrorResponse(error, message, problems, Instant.now());
        }
    }
}
