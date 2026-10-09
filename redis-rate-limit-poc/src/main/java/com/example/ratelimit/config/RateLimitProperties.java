package com.example.ratelimit.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed configuration for the rate limiter. Bound from {@code rate-limit.*} in application.yml. */
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** Global kill switch. When false no request is ever counted or rejected. */
    private boolean enabled = true;

    /** Redis key namespace. */
    private String keyPrefix = "rate-limit:v1";

    /**
     * CIDR blocks of reverse proxies whose {@code X-Forwarded-For} / {@code X-Real-IP} headers may be
     * trusted. Empty means no proxy header is trusted and the socket peer address is used.
     */
    private List<String> trustedProxies = List.of();

    /** Default behaviour when Redis is unreachable. Individual policies may override it. */
    private FailureMode onRedisError = FailureMode.FAIL_OPEN;

    /** Small grace added to the window when setting the Redis TTL, so a counter survives the boundary. */
    private Duration ttlGrace = Duration.ofSeconds(1);

    /** Routes that never consume quota, e.g. actuator probes. Ant patterns. */
    private List<String> excludedPaths = List.of("/actuator/**");

    /** HTTP methods that skip the limiter entirely. */
    private List<String> excludedMethods = List.of("OPTIONS");

    /** Fixed control-plane throttle. Config-only: never stored in or editable through the policy APIs. */
    @Valid
    private AdminProtection adminProtection = new AdminProtection();

    @Valid
    @NotEmpty
    private List<Policy> policies = List.of();

    public enum Identity {
        /** Socket peer IP (or trusted proxy header). For unauthenticated traffic. */
        IP,
        /** Stable authenticated principal name. For authenticated traffic. */
        USER
    }

    public enum FailureMode {
        /** Let the request through when the store is unavailable. */
        FAIL_OPEN,
        /** Reject with 503 when the store is unavailable. */
        FAIL_CLOSED
    }

    /**
     * One policy. {@code id} is the stable route identity used in Redis keys and metrics; the raw
     * request path is never used, so path parameters cannot leak into keys.
     */
    public record Policy(
            @Pattern(regexp = "[a-z0-9-]+") String id,
            @NotNull String method,
            @NotNull String path,
            @Min(1) int limit,
            @NotNull Duration window,
            @NotNull Identity identity,
            FailureMode onRedisError) {

        public FailureMode failureMode(FailureMode global) {
            return onRedisError != null ? onRedisError : global;
        }
    }

    /** Bound from {@code rate-limit.admin-protection.*}. */
    public static class AdminProtection {
        private boolean enabled = true;
        @Min(1)
        private int limit = 300;
        @NotNull
        private Duration window = Duration.ofMinutes(1);
        @Min(1)
        private int maxAuthFailures = 10;
        @NotNull
        private Duration failureWindow = Duration.ofMinutes(10);
        @NotNull
        private Duration lockout = Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public int getMaxAuthFailures() { return maxAuthFailures; }
        public void setMaxAuthFailures(int maxAuthFailures) { this.maxAuthFailures = maxAuthFailures; }
        public Duration getFailureWindow() { return failureWindow; }
        public void setFailureWindow(Duration failureWindow) { this.failureWindow = failureWindow; }
        public Duration getLockout() { return lockout; }
        public void setLockout(Duration lockout) { this.lockout = lockout; }
    }

    public AdminProtection getAdminProtection() {
        return adminProtection;
    }

    public void setAdminProtection(AdminProtection adminProtection) {
        this.adminProtection = adminProtection;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public FailureMode getOnRedisError() {
        return onRedisError;
    }

    public void setOnRedisError(FailureMode onRedisError) {
        this.onRedisError = onRedisError;
    }

    public Duration getTtlGrace() {
        return ttlGrace;
    }

    public void setTtlGrace(Duration ttlGrace) {
        this.ttlGrace = ttlGrace;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }

    public List<String> getExcludedMethods() {
        return excludedMethods;
    }

    public void setExcludedMethods(List<String> excludedMethods) {
        this.excludedMethods = excludedMethods;
    }

    public List<Policy> getPolicies() {
        return policies;
    }

    public void setPolicies(List<Policy> policies) {
        this.policies = policies;
    }
}
