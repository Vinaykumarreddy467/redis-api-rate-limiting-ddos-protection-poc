package com.example.ratelimit.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicyEnforcer;
import com.example.ratelimit.policy.Scope;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver.Identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies every policy that matches the request before the controller runs.
 *
 * <p>Registered after Spring Security's filter chain, so the {@code SecurityContext} is already
 * populated when a USER identity resolves.
 *
 * <p>Policies come from {@link PolicyEnforcer}, which reads administrator-managed documents out of
 * shared Redis. That is what makes an admin edit take effect on every instance without a restart. When
 * the managed store is unreadable the matcher falls back to the {@code application.yml} baseline; see
 * {@link PolicyMatcher} for why that fallback is necessary rather than merely convenient.
 *
 * <p>When several policies apply, all must allow. The first denial is reported, with the ids of the
 * policies already consulted so a client can see which rule bound it.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final PolicyEnforcer enforcer;
    private final RateLimitIdentityResolver identities;
    private final RateLimitMetrics metrics;
    private final RateLimitProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final com.example.ratelimit.policy.ExemptionStore exemptions;
    private final com.example.ratelimit.policy.EndpointExemptionService endpointExemptions;

    public RateLimitFilter(PolicyEnforcer enforcer, RateLimitIdentityResolver identities,
            RateLimitMetrics metrics, RateLimitProperties properties, ObjectMapper mapper, Clock clock,
            com.example.ratelimit.policy.ExemptionStore exemptions,
            com.example.ratelimit.policy.EndpointExemptionService endpointExemptions) {
        this.enforcer = enforcer;
        this.identities = identities;
        this.metrics = metrics;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.exemptions = exemptions;
        this.endpointExemptions = endpointExemptions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String method = request.getMethod();
        for (String excluded : properties.getExcludedMethods()) {
            if (excluded.equalsIgnoreCase(method)) {
                return true;
            }
        }
        String path = request.getRequestURI();
        for (String excluded : properties.getExcludedPaths()) {
            if (matcher.match(excluded, path)) {
                return true;
            }
        }
        // The control plane is never rate limited: a throttled admin could not undo the policy that is
        // throttling it.
        if (path.startsWith("/api/admin/")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String method = request.getMethod();
        String path = request.getRequestURI();

        // Exemptions are evaluated first: a matching exemption bypasses all rate-limit policies.
        try {
            for (var ex : exemptions.findAll()) {
                if (!ex.enabled()) continue;
                if (ex.method() != null && !"ANY".equalsIgnoreCase(ex.method())
                        && !ex.method().equalsIgnoreCase(method)) continue;
                if (ex.path() != null && matcher.match(ex.path(), path)) {
                    chain.doFilter(request, response);
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("exemption lookup failed for {} {}: {}", method, path, e.getMessage());
        }

        // Endpoint-level exemptions from policy groups bypass all rules.
        try {
            if (endpointExemptions.isExempt(method, path)) {
                chain.doFilter(request, response);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("endpoint exemption lookup failed for {} {}: {}", method, path, e.getMessage());
        }

        List<PolicyDocument> applicable = enforcer.applicablePolicies(method, path);
        if (applicable.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // Each policy uses its own scope's identity. USER falls back to IP when unauthenticated, so an
        // anonymous flood cannot bypass a user-scoped rule; GLOBAL uses one constant identity.
        var resolved = new ArrayList<PolicyEnforcer.ResolvedPolicy>(applicable.size());
        PolicyDocument activePolicy = applicable.get(0);
        PolicyEnforcer.Composition composition;
        try {
            for (PolicyDocument policy : applicable) {
                activePolicy = policy;
                if (policy.scope() == Scope.GLOBAL) {
                    resolved.add(new PolicyEnforcer.ResolvedPolicy(policy, Scope.GLOBAL.name(), "global"));
                } else if (policy.scope() == Scope.APPLICATION) {
                    resolved.add(new PolicyEnforcer.ResolvedPolicy(policy, Scope.APPLICATION.name(), "application"));
                } else {
                    Identity identity = identities.resolve(request, enforcer.storePolicy(policy));
                    resolved.add(new PolicyEnforcer.ResolvedPolicy(policy, identity.type(), identity.value()));
                }
            }

            if (resolved.isEmpty()) {
                // Tier mismatch or non-applicable policies: non-applicable policies do not charge.
                chain.doFilter(request, response);
                return;
            }

            composition = enforcer.enforceResolved(resolved, clock.millis());
        } catch (RateLimitStore.RateLimitStoreUnavailableException e) {
            PolicyDocument governing = activePolicy != null ? activePolicy : applicable.get(0);
            String outcomeIdentity = identityTypeFor(resolved, governing.id());
            metrics.record(RateLimitMetrics.Outcome.STORE_ERROR, governing.id(), outcomeIdentity);
            log.warn("rate limit store unavailable for policy {}", governing.id(), e);
            if (enforcer.failureModeFor(governing) == FailureMode.FAIL_CLOSED) {
                writeStoreUnavailable(response, request, governing);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (composition.consulted().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        PolicyDocument blocked = composition.blockedBy();
        if (blocked == null) {
            PolicyDocument governing = composition.consulted().get(0);
            String outcomeIdentity = identityTypeFor(resolved, governing.id());
            metrics.record(RateLimitMetrics.Outcome.ALLOWED, governing.id(), outcomeIdentity);
            if (composition.decision() != null) {
                response.setHeader("X-RateLimit-Limit", String.valueOf(composition.decision().limit()));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(composition.decision().remaining()));
            }
            if (composition.consulted().size() > 1) {
                // Make AND-composition observable to the client without leaking anything sensitive.
                response.setHeader("X-RateLimit-Policies",
                        composition.consulted().stream().map(PolicyDocument::id).toList().toString());
            }
            if (composition.leases().isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            // Concurrency permits are held for the whole request and released afterwards, so in-flight
            // work is what is capped rather than request starts.
            try {
                chain.doFilter(request, response);
            } finally {
                if (request.isAsyncStarted()) {
                    request.getAsyncContext().addListener(new ReleaseOnAsyncDone(resolved, composition));
                } else {
                    releaseLeases(resolved, composition);
                }
            }
            return;
        }

        String outcomeIdentity = identityTypeFor(resolved, blocked.id());
        metrics.record(RateLimitMetrics.Outcome.REJECTED, blocked.id(), outcomeIdentity);
        writeRateLimited(response, request, blocked, composition);
    }

    private static String identityTypeFor(List<PolicyEnforcer.ResolvedPolicy> resolved, String policyId) {
        for (PolicyEnforcer.ResolvedPolicy candidate : resolved) {
            if (candidate.policy().id().equals(policyId)) {
                return candidate.identityType();
            }
        }
        return RateLimitProperties.Identity.IP.name();
    }

    private void releaseLeases(List<PolicyEnforcer.ResolvedPolicy> resolved,
            PolicyEnforcer.Composition composition) {
        for (var entry : composition.leases().entrySet()) {
            resolved.stream()
                    .filter(r -> r.policy().id().equals(entry.getKey()))
                    .findFirst()
                    .ifPresent(r -> enforcer.release(r, entry.getValue()));
        }
    }

    /**
     * Releases concurrency permits when the servlet container finishes async processing. Without
     * this, a permit would be released when the initial filter call returns while the request is
     * still running, and the cap would stop meaning anything on async routes.
     */
    private final class ReleaseOnAsyncDone implements jakarta.servlet.AsyncListener {
        private final List<PolicyEnforcer.ResolvedPolicy> resolved;
        private final PolicyEnforcer.Composition composition;

        ReleaseOnAsyncDone(List<PolicyEnforcer.ResolvedPolicy> resolved,
                PolicyEnforcer.Composition composition) {
            this.resolved = resolved;
            this.composition = composition;
        }

        @Override
        public void onComplete(jakarta.servlet.AsyncEvent event) {
            releaseLeases(resolved, composition);
        }

        @Override
        public void onTimeout(jakarta.servlet.AsyncEvent event) {
            releaseLeases(resolved, composition);
        }

        @Override
        public void onError(jakarta.servlet.AsyncEvent event) {
            releaseLeases(resolved, composition);
        }

        @Override
        public void onStartAsync(jakarta.servlet.AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    private void writeRateLimited(HttpServletResponse response, HttpServletRequest request, PolicyDocument policy,
            PolicyEnforcer.Composition composition) throws IOException {
        long retryAfterSeconds = composition.decision().retryAfter().toSeconds();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Policy", policy.id());
        response.setHeader("X-RateLimit-Limit", String.valueOf(composition.decision().limit()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now(clock).toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", "Rate limit exceeded for this route. Retry after " + retryAfterSeconds + "s.");
        body.put("path", request.getRequestURI());
        body.put("policy", policy.id());
        body.put("algorithm", policy.algorithm() == null ? null : policy.algorithm().name());
        body.put("scope", policy.scope() == null ? null : policy.scope().name());
        body.put("limit", composition.decision().limit());
        body.put("windowSeconds", policy.window() == null ? null : policy.window().toSeconds());
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("consultedPolicies",
                composition.consulted().stream().map(PolicyDocument::id).toList());
        mapper.writeValue(response.getOutputStream(), body);
    }

    /** No quota was counted, so no X-RateLimit-Limit/Remaining headers are emitted here. */
    private void writeStoreUnavailable(HttpServletResponse response, HttpServletRequest request, PolicyDocument policy)
            throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Policy", policy.id());
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        mapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", Instant.now(clock).toString(),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "message", "Rate limiting is temporarily unavailable. Please retry later.",
                "path", request.getRequestURI(),
                "policy", policy.id(),
                "retryAfterSeconds", 5));
    }
}
