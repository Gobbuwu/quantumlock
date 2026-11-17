package com.quantumlock.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits how large a request body may be, so nobody can exhaust the server's memory by
 * posting a huge body. The largest legitimate body in this API is a few kilobytes
 * (an ML-KEM-1024 public key or ciphertext is 1568 bytes before Base64).
 *
 * <ul>
 *   <li>A request that declares a Content-Length above the limit is refused with 413
 *       before any of its body is read.</li>
 *   <li>A request that does not declare its length (chunked upload) is cut off with a
 *       {@link RequestTooLargeException} as soon as it has sent one byte too many.</li>
 * </ul>
 *
 * <p>Form posts are parsed by Tomcat itself, not through this stream. They are limited by
 * server.tomcat.max-http-form-post-size in application.yml, which is set to the same value.
 */
public final class RequestSizeLimitFilter implements Filter {

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            if (httpRequest.getContentLengthLong() > maxBytes) {
                Rejections.write(httpResponse, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "payload_too_large");
                return;
            }
            chain.doFilter(new LimitedRequest(httpRequest, maxBytes), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Hands out a body stream that counts what is read and stops at the limit. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called for this request");
            }
            if (stream == null) {
                stream = new LimitedServletInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                if (stream != null) {
                    throw new IllegalStateException("getInputStream() has already been called for this request");
                }
                String encoding = getCharacterEncoding();
                Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                stream = new LimitedServletInputStream(super.getInputStream(), maxBytes);
                reader = new BufferedReader(new InputStreamReader(stream, charset));
            }
            return reader;
        }
    }
}
