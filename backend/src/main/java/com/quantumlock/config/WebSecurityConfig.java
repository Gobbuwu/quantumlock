package com.quantumlock.config;

import com.quantumlock.security.AllowedHostsFilter;
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
 * security headers, then the Host allow list, then the request size limit.
 * The limits come from application.yml (quantumlock.security.*).
 */
@Configuration
public class WebSecurityConfig {

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
}
