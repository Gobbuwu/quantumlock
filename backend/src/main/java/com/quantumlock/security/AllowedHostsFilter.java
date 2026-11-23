package com.quantumlock.security;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses requests whose Host header is not on the allow list (default: localhost and 127.0.0.1).
 *
 * <p>Why this matters for an API that only listens on localhost: in a "DNS rebinding" attack a
 * web page on evil.example makes its own domain name point at 127.0.0.1, and the browser of the
 * developer who visits that page then talks to the local API as if it were evil.example. The
 * browser still sends "Host: evil.example", so checking the Host header stops the attack.
 *
 * <p>Set the ALLOWED_HOSTS environment variable (comma separated) if the API ever has to answer
 * on another name.
 */
public final class AllowedHostsFilter implements Filter {

    private final Set<String> allowedHosts;

    public AllowedHostsFilter(Collection<String> allowedHosts) {
        Set<String> normalized = new HashSet<>();
        for (String host : allowedHosts) {
            String value = normalize(host);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed host is required");
        }
        this.allowedHosts = Set.copyOf(normalized);
    }

    /** Builds the filter from a comma separated list such as "localhost,127.0.0.1". */
    public static AllowedHostsFilter fromCommaSeparated(String hosts) {
        return new AllowedHostsFilter(List.of(hosts.split(",")));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse
                && !isAllowed(httpRequest.getServerName())) {
            Rejections.write(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "host_not_allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    /** True when the host name of the request (the Host header without the port) is on the allow list. */
    public boolean isAllowed(String serverName) {
        return allowedHosts.contains(normalize(serverName));
    }

    /** Lower case, no surrounding spaces, no trailing dot and no IPv6 brackets, so equal names compare equal. */
    static String normalize(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
