package com.quantumlock.security;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds browser security headers to every response.
 *
 * <ul>
 *   <li>X-Content-Type-Options: nosniff - browsers must not guess the content type</li>
 *   <li>X-Frame-Options: DENY - the API can never be shown inside a frame (clickjacking)</li>
 *   <li>Referrer-Policy: no-referrer - URLs are never leaked to other sites</li>
 *   <li>Cache-Control: no-store - responses (later: keys and secrets) are never written to a cache</li>
 *   <li>Content-Security-Policy: default-src 'none' - a response can never load or run anything</li>
 * </ul>
 *
 * <p>The last two are strict by default and left off only for the Swagger UI page and its
 * files, which need to load their own scripts and styles. Any path that is not recognised as
 * Swagger UI gets the strict headers, so a trick with the URL can only make the response stricter.
 */
public final class SecurityHeadersFilter implements Filter {

    static final String STRICT_CSP = "default-src 'none'; frame-ancestors 'none'";

    private static final Pattern SWAGGER_UI_PATH = Pattern.compile("/swagger-ui(\\.html|/[A-Za-z0-9._-]*)");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            httpResponse.setHeader("X-Frame-Options", "DENY");
            httpResponse.setHeader("Referrer-Policy", "no-referrer");
            String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
            if (!isSwaggerUiPath(path)) {
                httpResponse.setHeader("Cache-Control", "no-store");
                httpResponse.setHeader("Content-Security-Policy", STRICT_CSP);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * True only for /swagger-ui.html and for a single plain file name directly under /swagger-ui/.
     * Anything unusual in the path ("..", "%", ";", a second "/") fails the match and gets the strict headers.
     */
    static boolean isSwaggerUiPath(String path) {
        return !path.contains("..") && SWAGGER_UI_PATH.matcher(path).matches();
    }
}
