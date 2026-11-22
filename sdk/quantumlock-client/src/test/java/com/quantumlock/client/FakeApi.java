package com.quantumlock.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/** A stand-in for the QuantumLock API on 127.0.0.1, built on the JDK's HTTP server. */
final class FakeApi implements AutoCloseable {

    record Request(String method, String path, String rawQuery, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : String.join(",", values);
        }
    }

    record Answer(int status, String body, Map<String, String> headers, long delayMillis) {
        static Answer json(int status, String body) {
            return new Answer(status, body, Map.of("Content-Type", "application/json"), 0);
        }
    }

    interface Responder {
        Answer answer(Request request);
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Responder responder = request -> Answer.json(404, "{\"error\":\"not_set_up\"}");

    FakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
            requests.add(request);
            Answer answer = responder.answer(request);
            try {
                if (answer.delayMillis() > 0) {
                    Thread.sleep(answer.delayMillis());
                }
                answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The client gave up (a timeout test): nothing to do.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    void respond(Responder responder) {
        this.responder = responder;
    }

    void respond(int status, String body) {
        respond(request -> Answer.json(status, body));
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    Request onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("expected 1 request, got " + requests.size());
        }
        return requests.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
