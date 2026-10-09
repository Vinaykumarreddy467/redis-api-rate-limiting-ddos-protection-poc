package com.example.ratelimit.config;

import com.example.ratelimit.policy.PolicyEnforcer;
import com.example.ratelimit.ratelimit.RateLimitFilter;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import com.example.ratelimit.ratelimit.RateLimitMetrics;
import com.example.ratelimit.ratelimit.RateLimitStore;
import com.example.ratelimit.web.AccessLogFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

@Configuration
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimitIdentityResolver rateLimitIdentityResolver(RateLimitProperties properties) {
        return new RateLimitIdentityResolver(properties);
    }

    @Bean
    RateLimitFilter rateLimitFilter(PolicyEnforcer enforcer, RateLimitIdentityResolver identities,
            RateLimitMetrics metrics, RateLimitProperties properties, ObjectMapper mapper, Clock clock,
            com.example.ratelimit.policy.ExemptionStore exemptions,
            com.example.ratelimit.policy.EndpointExemptionService endpointExemptions,
            com.example.ratelimit.traffic.TrafficRecorder traffic) {
        return new RateLimitFilter(enforcer, identities, metrics, properties, mapper, clock, exemptions,
                endpointExemptions, traffic);
    }

    /**
     * Ordered after Spring Security's chain (which registers at -100 by default) so the
     * SecurityContext is populated before we resolve a USER identity. See
     * docs/api-rate-limiting-poc.md for the ordering proof.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Access log runs BEFORE the rate limit filter so it wraps everything downstream and still
     * observes the final status when the limiter short-circuits with 429 or 503.
     * LOWEST_PRECEDENCE - 150 is numerically less than -100, so it is registered earlier.
     */
    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(RateLimitIdentityResolver identities) {
        var registration = new FilterRegistrationBean<>(new AccessLogFilter(identities));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 150);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    com.example.ratelimit.ratelimit.AdminProtectionFilter adminProtectionFilter(RateLimitStore store,
            RateLimitIdentityResolver identities, RateLimitProperties properties, ObjectMapper mapper,
            Clock clock) {
        return new com.example.ratelimit.ratelimit.AdminProtectionFilter(store, identities, properties,
                mapper, clock);
    }

    /**
     * Order -150 is numerically below Spring Security's -100, so this runs BEFORE authentication and
     * can count failed credential guesses on every path. (The rate limit and access log filters sit far above -100.)
     */
    @Bean
    FilterRegistrationBean<com.example.ratelimit.ratelimit.AdminProtectionFilter> adminProtectionRegistration(
            com.example.ratelimit.ratelimit.AdminProtectionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(-150);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
