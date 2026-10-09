package com.example.ratelimit.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.AdminProtection;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.config.RateLimitProperties.Policy;
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
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles the control plane ({@code /api/admin/**}, {@code /api/poc/**}) before authentication runs.
 *
 * <p>{@link RateLimitFilter} deliberately skips the control plane so an administrator can never be
 * locked out by a policy they are trying to fix. Left at that, HTTP Basic could be brute-forced at full
 * speed. This filter closes the gap with two fixed limits per client IP, taken from configuration only,
 * so nothing in the admin UI can loosen them:
 *
 * <ul>
 *   <li>a request cap ({@code limit} per {@code window}) on control-plane paths only;</li>
 *   <li>a failed-login lockout: {@link #onBadCredentials} records every bad Basic login on ANY path;
 *       once {@code maxAuthFailures} land within {@code failureWindow} the IP is locked for the full
 *       {@code lockout}. The lock is checked only for requests that carry an Authorization header, so
 *       callers without credentials (public routes, the console's anonymous probe) are never blocked.</li>
 * </ul>
 *
 * <p>Registered ahead of Spring Security for all paths. A request that has no Authorization header and is
 * not on a control-plane path never touches Redis here. Both limits fail closed (503).
 */
public class AdminProtectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminProtectionFilter.class);
    static final String REQUESTS_POLICY = "control-plane-requests";
    static final String LOCKOUT_POLICY = "control-plane-auth-failures";

    private final RateLimitStore store;
    private final RateLimitIdentityResolver identities;
    private final AdminProtection config;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AdminProtectionFilter(RateLimitStore store, RateLimitIdentityResolver identities,
            RateLimitProperties properties, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.identities = identities;
        this.config = properties.getAdminProtection();
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    /** servletPath + pathInfo (no ;params, already decoded) with duplicate slashes collapsed. */
    static String normalizedPath(HttpServletRequest request) {
        String path = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (path.isEmpty()) {
            path = request.getRequestURI() == null ? "" : request.getRequestURI();
        }
        return path.replaceAll("/{2,}", "/");
    }

    private static boolean isControlPlane(String path) {
        return path.equals("/api/admin") || path.startsWith("/api/admin/")
                || path.equals("/api/poc") || path.startsWith("/api/poc/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean credentialed = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        boolean controlPlane = isControlPlane(normalizedPath(request));
        if (!credentialed && !controlPlane) {
            chain.doFilter(request, response);
            return;
        }
        String ip = identities.clientIp(request);
        long now = clock.millis();
        try {
            if (credentialed) {
                Duration locked = store.controlPlaneLockRemaining(ip);
                if (!locked.isZero()) {
                    writeRejected(response, request, LOCKOUT_POLICY, locked,
                            "Too many failed sign-in attempts from this address.");
                    return;
                }
            }
            if (controlPlane) {
                RateLimitDecision requests = store.consume(requestsPolicy(), "CP", ip, now);
                if (!requests.allowed()) {
                    writeRejected(response, request, REQUESTS_POLICY, requests.retryAfter(),
                            "Too many control-plane requests from this address.");
                    return;
                }
            }
        } catch (RateLimitStore.RateLimitStoreUnavailableException e) {
            log.warn("control-plane throttle unavailable, refusing request: {}", e.getMessage());
            writeUnavailable(response, request);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Fired by Spring Security for each rejected username/password (any path); never for requests that
     * carry no credentials. The IP is resolved from the current request, so trusted-proxy rules apply.
     */
    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        if (!config.isEnabled()
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        try {
            store.controlPlaneRecordFailure(identities.clientIp(attrs.getRequest()), config.getMaxAuthFailures(),
                    config.getFailureWindow(), config.getLockout());
        } catch (RateLimitStore.RateLimitStoreUnavailableException e) {
            // The 401 still goes out; the next credentialed request sees the outage and gets 503.
            log.warn("could not record failed sign-in: {}", e.getMessage());
        }
    }

    private Policy requestsPolicy() {
        return new Policy(REQUESTS_POLICY, "ANY", "/api/admin/**", config.getLimit(), config.getWindow(),
                RateLimitProperties.Identity.IP, FailureMode.FAIL_CLOSED);
    }

    private void writeRejected(HttpServletResponse response, HttpServletRequest request, String policy,
            Duration retryAfter, String message) throws IOException {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Policy", policy);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        mapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", Instant.now(clock).toString(),
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "message", message + " Retry after " + seconds + "s.",
                "path", request.getRequestURI(),
                "policy", policy,
                "retryAfterSeconds", seconds));
    }

    private void writeUnavailable(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        mapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", Instant.now(clock).toString(),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "message", "The control plane is temporarily unavailable. Please retry later.",
                "path", request.getRequestURI(),
                "retryAfterSeconds", 5));
    }
}
