package com.example.ratelimit.ratelimit;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import com.example.ratelimit.policy.Algorithm;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.Scope;

/**
 * The single seam between HTTP handling and Redis. Keeping it an interface lets policy matching,
 * the filter, failure behaviour and concurrency be tested without a servlet container or Redis.
 */
public interface RateLimitStore {

    /**
     * Atomically count this request against {@code policy} for {@code identity} in the window that
     * contains {@code nowMillis}, and report the decision.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    RateLimitDecision consume(Policy policy, String identityType, String identity, long nowMillis);

    /**
     * Report the decision for {@code policy} and {@code identity} without spending quota.
     *
     * <p>Used by the default {@link #consumeAll} fallback and by standalone callers. Implementations
     * must not create quota state or change counters here.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    RateLimitDecision peek(Policy policy, String identityType, String identity, long nowMillis);

    /**
     * Control-plane lockout: time left on the lock for {@code ip}, or {@link java.time.Duration#ZERO}
     * when it is not locked out. Stores that do not support it must not be used with the admin filter.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    default java.time.Duration controlPlaneLockRemaining(String ip) {
        throw new UnsupportedOperationException("control-plane lockout not supported");
    }

    /**
     * Records one failed sign-in for {@code ip}. Atomically counts it within {@code countWindow}; when the
     * count reaches {@code maxFailures} the IP is locked for the full {@code lockout} and the count resets.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    default void controlPlaneRecordFailure(String ip, int maxFailures, java.time.Duration countWindow,
            java.time.Duration lockout) {
        throw new UnsupportedOperationException("control-plane lockout not supported");
    }

    /**
     * One policy charge inside an atomic batch. Carries the managed document rather than the legacy
     * fixed-window record so a heterogeneous batch can dispatch per algorithm.
     */
    record Charge(PolicyDocument policy, String identityType, String identity) {
    }

    /**
     * Outcome of one atomic batch.
     *
     * @param blockedIndex position of the denying charge in the input list, or -1 when all allowed
     * @param decision the denying charge's decision (with retry info), or the governing charge's
     *                 decision when everything was allowed
     * @param leases policy id to lease id for every concurrency permit this batch acquired; empty
     *               when no concurrency policy applied. The caller must release them.
     */
    record BatchDecision(int blockedIndex, RateLimitDecision decision,
            java.util.Map<String, String> leases) {

        public boolean allowed() {
            return blockedIndex < 0;
        }
    }

    /**
     * Decides and charges every policy atomically: either all counters move or none do.
     *
     * <p>The default does sequential peek-then-commit, which still leaves the check/commit race open
     * for stores that cannot do better. {@link RedisRateLimitStore} overrides this with a single Lua
     * script so the check and the charge are one Redis-side operation.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    default BatchDecision consumeAll(java.util.List<Charge> charges, long nowMillis) {
        if (charges.isEmpty()) {
            throw new IllegalArgumentException("consumeAll requires at least one charge");
        }
        for (int i = 0; i < charges.size(); i++) {
            var charge = charges.get(i);
            RateLimitDecision preflight = peek(fixedProjection(charge.policy()), charge.identityType(),
                    charge.identity(), nowMillis);
            if (!preflight.allowed()) {
                return new BatchDecision(i, preflight, java.util.Map.of());
            }
        }
        RateLimitDecision governing = null;
        for (int i = 0; i < charges.size(); i++) {
            var charge = charges.get(i);
            RateLimitDecision decision = consume(fixedProjection(charge.policy()), charge.identityType(),
                    charge.identity(), nowMillis);
            if (governing == null) {
                governing = decision;
            }
            if (!decision.allowed()) {
                return new BatchDecision(i, decision, java.util.Map.of());
            }
        }
        return new BatchDecision(-1, governing, java.util.Map.of());
    }

    /**
     * Releases one previously acquired concurrency permit. Only meaningful for stores that issue
     * leases; the default accepts the call and does nothing so test doubles keep compiling.
     */
    default void releaseConcurrency(PolicyDocument policy, String identityType, String identity,
            String leaseId) {
    }

    /**
     * Projects a managed fixed-window policy onto the legacy record. Only valid for
     * {@code FIXED_WINDOW}; anything else means the caller bypassed validation.
     */
    static Policy fixedProjection(PolicyDocument policy) {
        if (policy.algorithm() != Algorithm.FIXED_WINDOW) {
            throw new IllegalStateException("policy " + policy.id() + " selects " + policy.algorithm()
                    + ", which the legacy fixed-window path cannot enforce");
        }
        RateLimitProperties.Identity identity = policy.scope() == Scope.USER
                ? RateLimitProperties.Identity.USER
                : RateLimitProperties.Identity.IP;
        return new Policy(policy.id(), policy.method(), policy.path(), policy.limit(), policy.window(),
                identity, policy.onRedisError());
    }

    /** Thrown to signal "store unavailable" as distinct from "rejected by policy". */
    class RateLimitStoreUnavailableException extends RuntimeException {
        public RateLimitStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
