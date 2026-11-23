package com.quantumlock.security;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.Caller;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requires a valid API key on every request except a short list of public paths (task 3.3).
 *
 * <ul>
 *   <li>Deny by default: only the health check and the API documentation are public. Every other path,
 *       known or not, needs a key.</li>
 *   <li>The key is read from the X-API-Key header only, never from the URL. Exactly one such header.</li>
 *   <li>Every failure (no key, malformed, unknown, revoked, two headers) gets the same 401 and body.</li>
 *   <li>The key is never logged.</li>
 *   <li>Cross-site requests from a browser (KAN-36) get 403 before the key is checked and are not counted as
 *       failures, so a hostile web page cannot use up the failure limit of the computer it runs on.</li>
 *   <li>Rate limits (KAN-31, KAN-36): a valid key gets 429 once it has made too many requests in a minute.
 *       An address with too many failed key checks in a minute gets 429 for its failed checks, but a valid key
 *       is always checked first and never refused because of its address: every local client shares
 *       127.0.0.1, so one misbehaving program must not lock out the others. The address is the connection's
 *       own, never a header the caller could set.</li>
 * </ul>
 *
 * <p>Guessing a key stays impractical without the address lock: a key carries 256 random bits.
 *
 * <p>A custom header also stops cross-site requests from reading or writing data: a browser will not send it to
 * another site without CORS permission, and none is given.
 */
public final class ApiKeyAuthFilter implements Filter {

    /** Request attribute under which the accepted {@link Caller} is stored. */
    public static final String CALLER_ATTRIBUTE = ApiKeyAuthFilter.class.getName() + ".caller";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String HEALTH_PATH = "/api/v1/health";

    /** /v3/api-docs and its sub-documents such as /v3/api-docs/swagger-config. Plain names only. */
    private static final Pattern API_DOCS_PATH = Pattern.compile("/v3/api-docs(/[A-Za-z0-9._-]+)?");

    private final ApiKeyService apiKeys;
    private final RateLimiter failedChecksPerAddress;
    private final RateLimiter requestsPerKey;
    private final Predicate<String> allowedHost;

    /**
     * @param allowedHost true for a host name the API answers to (the Host allow list); an Origin header must name
     *                    one of them
     */
    public ApiKeyAuthFilter(ApiKeyService apiKeys, RateLimiter failedChecksPerAddress, RateLimiter requestsPerKey,
            Predicate<String> allowedHost) {
        this.apiKeys = apiKeys;
        this.failedChecksPerAddress = failedChecksPerAddress;
        this.requestsPerKey = requestsPerKey;
        this.allowedHost = allowedHost;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (isCrossSite(httpRequest, allowedHost)) {
            Rejections.write(httpResponse, HttpServletResponse.SC_FORBIDDEN, "cross_site_request");
            return;
        }

        Optional<Caller> caller;
        try {
            caller = presentedKey(httpRequest).flatMap(apiKeys::authenticate);
        } catch (RuntimeException e) {
            // For example the database is down. Logged by type only: the message could contain the query.
            log.error("API key check failed ({})", e.getClass().getSimpleName());
            Rejections.write(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "service_unavailable");
            return;
        }
        if (caller.isEmpty()) {
            String address = httpRequest.getRemoteAddr();
            long wait = failedChecksPerAddress.waitSeconds(address);
            if (wait > 0) {
                Rejections.tooManyRequests(httpResponse, wait);
                return;
            }
            if (failedChecksPerAddress.record(address)) {
                log.warn("Too many failed API key checks from {}: its requests are refused for up to a minute", address);
            }
            Rejections.write(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }
        long wait = requestsPerKey.acquire(caller.get().apiKeyId().toString());
        if (wait > 0) {
            Rejections.tooManyRequests(httpResponse, wait);
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    /**
     * True when a browser says the request comes from another site: a Sec-Fetch-Site header other than
     * "same-origin" or "none" (typed into the address bar), or an Origin header whose host is not on the Host allow
     * list. Browsers send Sec-Fetch-Site with every request, including image loads, which carry no Origin. The
     * dashboard reaches the API through its own server, so its requests are same-origin. Programs such as the SDK,
     * curl or the demo scripts send neither header and are not affected.
     */
    static boolean isCrossSite(HttpServletRequest request, Predicate<String> allowedHost) {
        for (String site : Collections.list(request.getHeaders("Sec-Fetch-Site"))) {
            String value = site.trim().toLowerCase(Locale.ROOT);
            if (!value.equals("same-origin") && !value.equals("none")) {
                return true;
            }
        }
        for (String origin : Collections.list(request.getHeaders("Origin"))) {
            if (!isAllowedOrigin(origin, allowedHost)) {
                return true;
            }
        }
        return false;
    }

    /** An http or https origin on an allowed host, any port. "null" (a sandboxed page or a file) is refused. */
    private static boolean isAllowedOrigin(String origin, Predicate<String> allowedHost) {
        try {
            URI uri = new URI(origin.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && allowedHost.test(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The one X-API-Key header. Empty when it is missing or sent more than once. */
    private static Optional<String> presentedKey(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(ApiKeys.HEADER));
        return values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
    }

    /**
     * True only for the exact health path and the documentation pages. Anything unusual in the path
     * ("..", "%", ";", a trailing "/") fails the match and needs a key, so a trick can only make it stricter.
     */
    static boolean isPublicPath(String path) {
        if (path.contains("..")) {
            return false;
        }
        return HEALTH_PATH.equals(path)
                || SecurityHeadersFilter.isSwaggerUiPath(path)
                || API_DOCS_PATH.matcher(path).matches();
    }
}
