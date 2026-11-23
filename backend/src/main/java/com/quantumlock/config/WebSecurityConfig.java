package com.quantumlock.config;

import java.time.Clock;
import java.time.Duration;

import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.security.AllowedHostsFilter;
import com.quantumlock.security.ApiKeyAuthFilter;
import com.quantumlock.security.RateLimiter;
import com.quantumlock.security.RequestSizeLimitFilter;
import com.quantumlock.security.SecurityHeadersFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the security filters. They run before any controller, in this order:
 * security headers, then the Host allow list, then the request size limit, then the API-key check.
 * The limits come from application.yml (quantumlock.security.*).
 */
@Configuration
public class WebSecurityConfig {

    /** Largest rate limit that can be set: high enough for any load one backend can serve. */
    static final int MAX_PER_MINUTE = 1_000_000;

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        // ERROR as well, so error pages produced by the server itself (404, 405 ...) carry the headers too.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AllowedHostsFilter> allowedHostsFilter(
            @Value("${quantumlock.security.allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        FilterRegistrationBean<AllowedHostsFilter> registration =
                new FilterRegistrationBean<>(AllowedHostsFilter.fromCommaSeparated(allowedHosts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${quantumlock.security.max-request-bytes:65536}") long maxRequestBytes) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(maxRequestBytes));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }

    /** Most requests one API key may make in a minute (KAN-31). */
    @Bean
    public RateLimiter requestsPerKeyLimiter(
            @Value("${quantumlock.rate-limit.requests-per-minute:120}") int perMinute) {
        return limiter(perMinute, "RATE_LIMIT_PER_MINUTE");
    }

    /** Most failed API-key checks one client address may cause in a minute (KAN-31). */
    @Bean
    public RateLimiter failedChecksPerAddressLimiter(
            @Value("${quantumlock.rate-limit.failed-checks-per-minute:20}") int perMinute) {
        return limiter(perMinute, "RATE_LIMIT_FAILED_CHECKS_PER_MINUTE");
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyService apiKeys,
            RateLimiter failedChecksPerAddressLimiter, RateLimiter requestsPerKeyLimiter,
            FilterRegistrationBean<AllowedHostsFilter> allowedHostsFilter) {
        // The same allow list as the Host check: an Origin header must name one of these hosts (KAN-36).
        AllowedHostsFilter allowedHosts = allowedHostsFilter.getFilter();
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(
                apiKeys, failedChecksPerAddressLimiter, requestsPerKeyLimiter, allowedHosts::isAllowed));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        return registration;
    }

    private static RateLimiter limiter(int perMinute, String setting) {
        if (perMinute < 1 || perMinute > MAX_PER_MINUTE) {
            throw new IllegalStateException(setting + " must be 1 to " + MAX_PER_MINUTE + ".");
        }
        return new RateLimiter(perMinute, Duration.ofMinutes(1), RateLimiter.DEFAULT_MAX_ENTRIES, Clock.systemUTC());
    }
}
