package com.quantumlock.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import com.quantumlock.auth.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Attacks the running application over real HTTP connections (embedded Tomcat on a random port)
 * and checks that each attack is refused. Requests are written by hand on a socket because some
 * of them (a forged Host header, TRACE, an odd path) cannot be sent with a normal HTTP client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApiKeyService apiKeys;

    /** A valid key of the demo team, for tests that must get past the API-key check. */
    private String demoKey() {
        return apiKeys.issue(UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51"), "test").key();
    }

    @Test
    void healthResponseCarriesSecurityHeaders() throws IOException {
        String response = send(get("/api/v1/health", "localhost"));

        assertEquals(200, statusOf(response));
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
        assertEquals("DENY", header(response, "X-Frame-Options"));
        assertEquals("no-referrer", header(response, "Referrer-Policy"));
        assertEquals("no-store", header(response, "Cache-Control"));
        assertEquals("default-src 'none'; frame-ancestors 'none'", header(response, "Content-Security-Policy"));
    }

    @Test
    void healthResponseDoesNotRevealSoftwareOrVersions() throws IOException {
        String response = send(get("/api/v1/health", "localhost"));

        assertNull(header(response, "Server"));
        assertNull(header(response, "X-Powered-By"));
        String body = bodyOf(response);
        assertTrue(body.contains("\"cryptoProvider\":\"UP\""), body);
        assertFalse(body.contains("BouncyCastle"), body);
        assertFalse(body.contains("Tomcat"), body);
    }

    @Test
    void requestForAnotherHostNameIsRefused() throws IOException {
        // What a browser sends during a DNS rebinding attack: the attacker's own name in the Host header.
        String response = send(get("/api/v1/health", "evil.example"));

        assertEquals(400, statusOf(response));
        assertEquals("{\"error\":\"host_not_allowed\"}", bodyOf(response));
    }

    @Test
    void hostNameThatOnlyStartsLikeLocalhostIsRefused() throws IOException {
        assertEquals(400, statusOf(send(get("/api/v1/health", "localhost.evil.example"))));
        assertEquals(400, statusOf(send(get("/api/v1/health", "127.0.0.1.evil.example"))));
    }

    @Test
    void loopbackAddressWithPortIsAccepted() throws IOException {
        assertEquals(200, statusOf(send(get("/api/v1/health", "127.0.0.1:" + port))));
    }

    @Test
    void oversizedBodyIsRefusedWith413() throws IOException {
        byte[] body = new byte[70_000];
        String head = "POST /api/v1/health HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        String response = send(head.getBytes(StandardCharsets.ISO_8859_1), body);

        assertEquals(413, statusOf(response));
        assertEquals("{\"error\":\"payload_too_large\"}", bodyOf(response));
    }

    @Test
    void traceMethodIsRefused() throws IOException {
        String request = "TRACE /api/v1/health HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "X-Secret: do-not-echo-me\r\n"
                + "Connection: close\r\n\r\n";

        String response = send(request.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(405, statusOf(response));
        assertFalse(response.contains("do-not-echo-me"), "TRACE must not echo request headers back");
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
    }

    @Test
    void unknownPathGives404WithoutInternalDetails() throws IOException {
        String response = send(get("/api/v1/does-not-exist", "localhost", demoKey()));

        assertEquals(404, statusOf(response));
        String body = bodyOf(response);
        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("\"trace\""), body);
        assertFalse(body.contains("org.springframework"), body);
        assertFalse(body.contains("does-not-exist"), "the requested path must not be echoed back: " + body);
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
    }

    @Test
    void developerAndConfigurationPathsAreNotServed() throws IOException {
        String[] paths = {"/actuator", "/actuator/health", "/actuator/env", "/actuator/heapdump", "/h2-console",
                "/.env", "/backend/.env", "/application.yml", "/.git/config"};

        String key = demoKey();
        for (String path : paths) {
            // Without a key the API-key check refuses first; with one, the path simply does not exist.
            assertEquals(401, statusOf(send(get(path, "localhost"))), path);
            assertEquals(404, statusOf(send(get(path, "localhost", key))), path);
        }
    }

    @Test
    void otherWebSitesGetNoCorsPermission() throws IOException {
        // No CORS is configured, so a browser must not let a page from another site read the answer.
        String request = "GET /api/v1/health HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Origin: https://evil.example\r\n"
                + "Connection: close\r\n\r\n";

        String response = send(request.getBytes(StandardCharsets.ISO_8859_1));

        assertNull(header(response, "Access-Control-Allow-Origin"));
        assertNull(header(response, "Access-Control-Allow-Credentials"));
    }

    @Test
    void pathTrickCannotSwitchOffTheStrictHeaders() throws IOException {
        // Swagger UI pages are the only ones without the strict CSP. A path that merely starts like one must not qualify.
        String response = send(get("/swagger-ui/../api/v1/health", "localhost"));

        assertEquals("default-src 'none'; frame-ancestors 'none'", header(response, "Content-Security-Policy"));
        assertEquals("no-store", header(response, "Cache-Control"));
    }

    // ---- helpers: a minimal HTTP client on a plain socket ----

    private static byte[] get(String path, String host) {
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Connection: close\r\n\r\n";
        return request.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] get(String path, String host, String apiKey) {
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "X-API-Key: " + apiKey + "\r\n"
                + "Connection: close\r\n\r\n";
        return request.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Sends the given parts one after the other and returns everything the server answers until it closes. */
    private String send(byte[]... parts) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            try {
                for (byte[] part : parts) {
                    out.write(part);
                }
                out.flush();
            } catch (IOException e) {
                // The server may answer and close before the whole body is sent. The answer is still readable.
            }
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    answer.write(buffer, 0, count);
                }
            } catch (IOException e) {
                // A connection reset after the answer was sent is fine. Whatever arrived is checked by the test.
            }
            return answer.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static int statusOf(String response) {
        // Status line: "HTTP/1.1 200 ..."
        return Integer.parseInt(response.substring(9, 12));
    }

    /** Value of a response header, or null when the header is absent. */
    private static String header(String response, String name) {
        String head = response.substring(0, endOfHead(response));
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String bodyOf(String response) {
        return response.substring(endOfHead(response) + 4);
    }

    private static int endOfHead(String response) {
        int end = response.indexOf("\r\n\r\n");
        assertTrue(end > 0, "Not an HTTP response: " + response);
        return end;
    }
}
