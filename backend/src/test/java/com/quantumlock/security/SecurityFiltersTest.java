package com.quantumlock.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the decisions the security filters make. Plain JUnit, no Spring context.
 * The filters are attacked end to end, over real HTTP, in {@link ApiSecurityTest}.
 */
class SecurityFiltersTest {

    // ---- Host allow list ----

    @Test
    void defaultHostsAreAllowedHoweverTheyAreWritten() {
        AllowedHostsFilter filter = AllowedHostsFilter.fromCommaSeparated("localhost,127.0.0.1");

        assertTrue(filter.isAllowed("localhost"));
        assertTrue(filter.isAllowed("LOCALHOST"));
        assertTrue(filter.isAllowed("localhost."));
        assertTrue(filter.isAllowed("127.0.0.1"));
    }

    @Test
    void otherHostsAreRefused() {
        AllowedHostsFilter filter = AllowedHostsFilter.fromCommaSeparated("localhost,127.0.0.1");

        assertFalse(filter.isAllowed("evil.example"));
        assertFalse(filter.isAllowed("localhost.evil.example"));
        assertFalse(filter.isAllowed("127.0.0.1.evil.example"));
        assertFalse(filter.isAllowed("evil-localhost"));
        assertFalse(filter.isAllowed("0.0.0.0"));
        assertFalse(filter.isAllowed("[::1]"));
        assertFalse(filter.isAllowed(""));
        assertFalse(filter.isAllowed(null));
    }

    @Test
    void extraHostsCanBeConfigured() {
        AllowedHostsFilter filter = AllowedHostsFilter.fromCommaSeparated(" localhost , [::1], api.example.org ");

        assertTrue(filter.isAllowed("[::1]"));
        assertTrue(filter.isAllowed("API.example.org"));
        assertFalse(filter.isAllowed("127.0.0.1"));
    }

    @Test
    void anEmptyAllowListIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () -> AllowedHostsFilter.fromCommaSeparated(" , "));
        assertThrows(IllegalArgumentException.class, () -> new AllowedHostsFilter(List.of()));
    }

    // ---- Security headers: which paths are exempt from the strict CSP ----

    @Test
    void onlyRealSwaggerUiPathsSkipTheStrictHeaders() {
        assertTrue(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui.html"));
        assertTrue(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/index.html"));
        assertTrue(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/swagger-ui-bundle.js"));

        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/api/v1/health"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/v3/api-docs"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/"));
    }

    @Test
    void pathTricksDoNotCountAsSwaggerUi() {
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/../api/v1/health"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/.."));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/%2e%2e/api/v1/health"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/..;/api/v1/health"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/index.html;x=1"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui/a/b"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui.html/x"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("//swagger-ui/index.html"));
        assertFalse(SecurityHeadersFilter.isSwaggerUiPath("/swagger-ui\\index.html"));
    }

    // ---- Request size limit ----

    @Test
    void bodyUpToTheLimitIsReadCompletely() throws IOException {
        assertEquals(10, new LimitedServletInputStream(new Body(10), 64).readAllBytes().length);
        assertEquals(64, new LimitedServletInputStream(new Body(64), 64).readAllBytes().length);
        assertEquals(0, new LimitedServletInputStream(new Body(0), 64).readAllBytes().length);
    }

    @Test
    void bodyOneByteOverTheLimitIsCutOff() {
        LimitedServletInputStream stream = new LimitedServletInputStream(new Body(65), 64);

        assertThrows(RequestTooLargeException.class, stream::readAllBytes);
    }

    @Test
    void hugeBodyIsCutOffWithoutBeingPulledIntoMemory() {
        Body body = new Body(5_000_000);
        LimitedServletInputStream stream = new LimitedServletInputStream(body, 65_536);

        assertThrows(RequestTooLargeException.class, stream::readAllBytes);
        assertEquals(65_537, body.bytesHandedOut, "at most one byte past the limit may be taken from the connection");
    }

    @Test
    void readingByteByByteIsLimitedToo() throws IOException {
        LimitedServletInputStream stream = new LimitedServletInputStream(new Body(100), 3);

        assertEquals(7, stream.read());
        assertEquals(7, stream.read());
        assertEquals(7, stream.read());
        assertThrows(RequestTooLargeException.class, stream::read);
        // It keeps failing: the caller cannot simply try again to get the rest.
        assertThrows(RequestTooLargeException.class, stream::read);
        assertThrows(RequestTooLargeException.class, () -> stream.read(new byte[8], 0, 8));
    }

    @Test
    void theLimitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RequestSizeLimitFilter(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestSizeLimitFilter(-1));
    }

    /** A request body of the given size (every byte is 7) that counts how many bytes were taken from it. */
    private static final class Body extends ServletInputStream {

        private final ByteArrayInputStream content;
        private int bytesHandedOut;

        Body(int size) {
            byte[] bytes = new byte[size];
            java.util.Arrays.fill(bytes, (byte) 7);
            this.content = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int value = content.read();
            if (value != -1) {
                bytesHandedOut++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = content.read(buffer, offset, length);
            if (count > 0) {
                bytesHandedOut += count;
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return content.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void onlyHealthAndDocsArePublic() {
        for (String path : new String[] {"/api/v1/health", "/v3/api-docs", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html"}) {
            assertTrue(ApiKeyAuthFilter.isPublicPath(path), path);
        }
        for (String path : new String[] {"/api/v1/keys", "/api/v1/health/", "/api/v1/health;x", "/api/v1/HEALTH",
                "/api/v1/health/../keys", "/v3/api-docs/../../api/v1/keys", "/swagger-ui/../api/v1/keys",
                "/v3/api-docs/a/b", "/", "", "/actuator"}) {
            assertFalse(ApiKeyAuthFilter.isPublicPath(path), path);
        }
    }
}
