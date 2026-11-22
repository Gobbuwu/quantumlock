package com.quantumlock.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 * </ul>
 *
 * <p>A custom header also stops cross-site requests: a browser will not send it to another site
 * without CORS permission, and none is given.
 */
public final class ApiKeyAuthFilter implements Filter {

    /** Request attribute under which the accepted {@link Caller} is stored. */
    public static final String CALLER_ATTRIBUTE = ApiKeyAuthFilter.class.getName() + ".caller";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String HEALTH_PATH = "/api/v1/health";

    /** /v3/api-docs and its sub-documents such as /v3/api-docs/swagger-config. Plain names only. */
    private static final Pattern API_DOCS_PATH = Pattern.compile("/v3/api-docs(/[A-Za-z0-9._-]+)?");

    private final ApiKeyService apiKeys;

    public ApiKeyAuthFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
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
            Rejections.write(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
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
