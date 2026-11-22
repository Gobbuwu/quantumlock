package com.quantumlock.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs the whole sample app, with the real starter and client, against a stand-in for the API. */
@ExtendWith(OutputCaptureExtension.class)
class SampleApplicationTest {

    static final String API_KEY = "ql_test_" + "C".repeat(43);
    static final UUID ID = UUID.randomUUID();
    static final byte[] CIPHERTEXT = new byte[1568];
    static final byte[] SECRET = new byte[32];

    static {
        for (int i = 0; i < CIPHERTEXT.length; i++) {
            CIPHERTEXT[i] = (byte) (i * 7);
        }
        for (int i = 0; i < SECRET.length; i++) {
            SECRET[i] = (byte) (i + 100);
        }
    }

    HttpServer api;
    final List<String> calls = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        api.stop(0);
    }

    @Test
    void generatesAKeyAndAgreesOnASecret(CapturedOutput output) throws Exception {
        start(SECRET);

        assertThat(runApp()).isEqualTo(0);

        assertThat(calls).containsExactly(
                "POST /api/v1/keys/generate",
                "POST /api/v1/keys/" + ID + "/encapsulate",
                "POST /api/v1/keys/" + ID + "/decapsulate");
        assertThat(output).contains("Generated ML-KEM-1024 key pair " + ID)
                .contains("Shared secrets match: true")
                .doesNotContain(API_KEY)
                .doesNotContain(Base64.getEncoder().encodeToString(SECRET));
    }

    @Test
    void exitsWithOneWhenTheSecretsDiffer(CapturedOutput output) throws Exception {
        byte[] other = SECRET.clone();
        other[0] ^= 1;
        start(other);

        assertThat(runApp()).isEqualTo(1);
        assertThat(output).contains("Shared secrets match: false");
    }

    @Test
    void exitsWithOneAndASafeMessageWhenTheKeyIsRefused(CapturedOutput output) throws Exception {
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/", exchange -> send(exchange, 401, "{\"error\":\"unauthorized\"}"));
        api.start();

        assertThat(runApp()).isEqualTo(1);
        assertThat(output).contains("QuantumLock call failed: QuantumLock API error 401 unauthorized")
                .doesNotContain(API_KEY);
    }

    private int runApp() {
        ConfigurableApplicationContext context = SpringApplication.run(SampleApplication.class,
                "--quantumlock.api-url=http://127.0.0.1:" + api.getAddress().getPort(),
                "--quantumlock.api-key=" + API_KEY);
        return SpringApplication.exit(context);
    }

    /** A stand-in API: decapsulate answers with {@code decapsulated}, encapsulate always with SECRET. */
    private void start(byte[] decapsulated) throws IOException {
        String b64Ciphertext = Base64.getEncoder().encodeToString(CIPHERTEXT);
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + path);
            if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else if (path.equals("/api/v1/keys/generate")) {
                send(exchange, 201, "{\"keyPairId\":\"" + ID + "\",\"algorithm\":\"ML-KEM-1024\",\"status\":\"active\","
                        + "\"publicKey\":\"AAEC\",\"createdAt\":\"2026-11-22T10:15:30Z\"}");
            } else if (path.endsWith("/encapsulate")) {
                send(exchange, 200, "{\"keyPairId\":\"" + ID + "\",\"ciphertext\":\"" + b64Ciphertext
                        + "\",\"sharedSecret\":\"" + Base64.getEncoder().encodeToString(SECRET) + "\"}");
            } else if (path.endsWith("/decapsulate") && body.equals("{\"ciphertext\":\"" + b64Ciphertext + "\"}")) {
                send(exchange, 200, "{\"keyPairId\":\"" + ID + "\",\"sharedSecret\":\""
                        + Base64.getEncoder().encodeToString(decapsulated) + "\"}");
            } else {
                send(exchange, 400, "{\"error\":\"invalid_request\"}");
            }
        });
        api.start();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
