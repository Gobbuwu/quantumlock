package com.quantumlock.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the QuantumLock API (version 1).
 *
 * <pre>{@code
 * try (QuantumLockClient client = QuantumLockClient.builder()
 *         .apiUrl("http://127.0.0.1:8080")
 *         .apiKey(System.getenv("QUANTUMLOCK_API_KEY"))
 *         .build()) {
 *     KeyPairInfo keyPair = client.generateKeyPair(Algorithm.ML_KEM_1024);
 * }
 * }</pre>
 *
 * <p>Security rules this client follows:
 * <ul>
 * <li>The API key is sent in the {@code X-API-Key} header only, never in a URL, and never appears in
 * {@link #toString()}, in an exception message or in a log (this client does not log at all).</li>
 * <li>Plain {@code http://} is refused unless the API runs on this computer (localhost, 127.0.0.1 or ::1).
 * Anywhere else the key and the shared secrets must travel over {@code https://}.</li>
 * <li>Redirects are never followed, so the key is never sent to a server other than the one configured.</li>
 * <li>Every call has a connect timeout and a total timeout. Responses over 16 MiB are refused.</li>
 * <li>Responses are checked strictly: every field must be present, ids must match the request, a shared
 * secret must be 32 bytes, a ciphertext 1568 bytes and a signature 3309 bytes.</li>
 * </ul>
 *
 * <p>An instance is immutable and safe to share between threads. Close it when done to free its threads.
 */
public final class QuantumLockClient implements AutoCloseable {

    /** The request header that carries the API key. */
    public static final String API_KEY_HEADER = "X-API-Key";
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Largest response body accepted, in bytes. */
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    /** Length of an ML-DSA-65 signature in bytes (FIPS 204). */
    public static final int SIGNATURE_LENGTH = 3309;
    /** Largest message the API signs or verifies, in bytes. */
    public static final int MAX_MESSAGE_LENGTH = 32 * 1024;
    /** Largest context string, in bytes (FIPS 204). */
    public static final int MAX_CONTEXT_LENGTH = 255;

    /** Same format the QuantumLock server issues and checks. */
    private static final Pattern API_KEY_FORMAT = Pattern.compile("ql_(test|live)_[A-Za-z0-9_-]{43}");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String baseUrl;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final JsonMapper json;

    private QuantumLockClient(Builder builder) {
        this.baseUrl = checkedBaseUrl(builder.apiUrl);
        this.apiKey = checkedApiKey(builder.apiKey);
        this.requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(positive(builder.connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Generates a new key pair. The private key is created and stored, encrypted, on the server only. */
    public KeyPairInfo generateKeyPair(Algorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        String body = json.writeValueAsString(Map.of("algorithm", algorithm.apiName()));
        return keyPair(call("POST", "/api/v1/keys/generate", body, 201));
    }

    /** All key pairs of the account the API key belongs to, newest first. */
    public List<KeyPairInfo> listKeyPairs() {
        JsonNode list = call("GET", "/api/v1/keys", null, 200);
        if (!list.isArray()) {
            throw new QuantumLockResponseException("response is not a list");
        }
        List<KeyPairInfo> keyPairs = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            keyPairs.add(keyPair(item));
        }
        return List.copyOf(keyPairs);
    }

    /** One key pair of the account. Throws {@link QuantumLockApiException} "key_not_found" for any other id. */
    public KeyPairInfo getKeyPair(UUID keyPairId) {
        Objects.requireNonNull(keyPairId, "keyPairId");
        KeyPairInfo keyPair = keyPair(call("GET", "/api/v1/keys/" + keyPairId, null, 200));
        requireSameId(keyPairId, keyPair.keyPairId());
        return keyPair;
    }

    /**
     * Makes a new shared secret for an active ML-KEM-1024 key pair, and the ciphertext that carries it.
     * Close the result (or its secret) when done.
     */
    public Encapsulation encapsulate(UUID keyPairId) {
        Objects.requireNonNull(keyPairId, "keyPairId");
        byte[] raw = callRaw("POST", "/api/v1/keys/" + keyPairId + "/encapsulate", null, 200);
        try {
            JsonNode body = parse(raw);
            requireSameId(keyPairId, uuid(body, "keyPairId"));
            byte[] ciphertext = base64(body, "ciphertext");
            return new Encapsulation(keyPairId, ciphertext, new SharedSecret(base64(body, "sharedSecret")));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /**
     * Gets the shared secret out of a 1568-byte ML-KEM-1024 ciphertext, using the private key of an active
     * key pair. A modified ciphertext gives a different secret, not an error (FIPS 203 implicit rejection).
     * Close the result when done.
     */
    public SharedSecret decapsulate(UUID keyPairId, byte[] ciphertext) {
        Objects.requireNonNull(keyPairId, "keyPairId");
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (ciphertext.length != Encapsulation.CIPHERTEXT_LENGTH) {
            throw new IllegalArgumentException("An ML-KEM-1024 ciphertext is " + Encapsulation.CIPHERTEXT_LENGTH
                    + " bytes, this one is " + ciphertext.length);
        }
        String request = json.writeValueAsString(Map.of("ciphertext", Base64.getEncoder().encodeToString(ciphertext)));
        byte[] raw = callRaw("POST", "/api/v1/keys/" + keyPairId + "/decapsulate", request, 200);
        try {
            JsonNode body = parse(raw);
            requireSameId(keyPairId, uuid(body, "keyPairId"));
            return new SharedSecret(base64(body, "sharedSecret"));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Signs a message with the private key of an active ML-DSA-65 key pair, with no context string. */
    public byte[] sign(UUID keyPairId, byte[] message) {
        return sign(keyPairId, message, new byte[0]);
    }

    /**
     * Signs a message (1 byte to 32 KiB) with the private key of an active ML-DSA-65 key pair (FIPS 204, hedged).
     * The context string (at most 255 bytes, may be empty) binds the signature to a purpose, for example
     * "invoices": the same context must be given to verify it. Returns the 3309-byte signature, which is not secret.
     */
    public byte[] sign(UUID keyPairId, byte[] message, byte[] context) {
        Objects.requireNonNull(keyPairId, "keyPairId");
        checkMessageAndContext(message, context);
        Map<String, String> fields = Map.of("message", Base64.getEncoder().encodeToString(message),
                "context", Base64.getEncoder().encodeToString(context));
        JsonNode body = call("POST", "/api/v1/keys/" + keyPairId + "/sign", json.writeValueAsString(fields), 200);
        requireSameId(keyPairId, uuid(body, "keyPairId"));
        byte[] signature = base64(body, "signature");
        if (signature.length != SIGNATURE_LENGTH) {
            throw new QuantumLockResponseException("signature has the wrong length");
        }
        return signature;
    }

    /** Checks a signature made with no context string. */
    public boolean verify(UUID keyPairId, byte[] message, byte[] signature) {
        return verify(keyPairId, message, signature, new byte[0]);
    }

    /**
     * True when the signature is valid for the message and context under the public key of an active ML-DSA-65
     * key pair. A signature that does not match gives false, not an exception.
     */
    public boolean verify(UUID keyPairId, byte[] message, byte[] signature, byte[] context) {
        Objects.requireNonNull(keyPairId, "keyPairId");
        Objects.requireNonNull(signature, "signature");
        checkMessageAndContext(message, context);
        if (signature.length != SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("An ML-DSA-65 signature is " + SIGNATURE_LENGTH + " bytes, this one is "
                    + signature.length);
        }
        Base64.Encoder b64 = Base64.getEncoder();
        Map<String, String> fields = Map.of("message", b64.encodeToString(message),
                "signature", b64.encodeToString(signature), "context", b64.encodeToString(context));
        JsonNode body = call("POST", "/api/v1/keys/" + keyPairId + "/verify", json.writeValueAsString(fields), 200);
        requireSameId(keyPairId, uuid(body, "keyPairId"));
        JsonNode valid = body.get("valid");
        if (valid == null || !valid.isBoolean()) {
            throw new QuantumLockResponseException("response field valid is missing");
        }
        return valid.booleanValue();
    }

    private static void checkMessageAndContext(byte[] message, byte[] context) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
        if (message.length == 0 || message.length > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("A message must be 1 to " + MAX_MESSAGE_LENGTH + " bytes, this one is "
                    + message.length);
        }
        if (context.length > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("A context must be at most " + MAX_CONTEXT_LENGTH
                    + " bytes, this one is " + context.length);
        }
    }

    /** Frees the HTTP client's threads and connections. */
    @Override
    public void close() {
        http.close();
    }

    @Override
    public String toString() {
        return "QuantumLockClient[apiUrl=" + baseUrl + ", apiKey=hidden]";
    }

    // ---- HTTP ---------------------------------------------------------------------------------------

    private JsonNode call(String method, String path, String body, int expectedStatus) {
        return parse(callRaw(method, path, body, expectedStatus));
    }

    /** Sends one request and returns the body of a response with the expected status. */
    private byte[] callRaw(String method, String path, String body, int expectedStatus) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        int status;
        byte[] responseBody;
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            try (InputStream in = response.body()) {
                responseBody = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuantumLockTransportException("interrupted while calling the QuantumLock API", e);
        } catch (IOException e) {
            throw new QuantumLockTransportException("could not reach the QuantumLock API", e);
        }

        if (responseBody.length > MAX_RESPONSE_BYTES) {
            Arrays.fill(responseBody, (byte) 0);
            throw new QuantumLockResponseException("response is larger than " + MAX_RESPONSE_BYTES + " bytes");
        }
        if (status == expectedStatus) {
            return responseBody;
        }
        try {
            if (status >= 400 && status <= 599) {
                throw new QuantumLockApiException(status, errorCode(responseBody));
            }
            throw new QuantumLockResponseException("unexpected HTTP status " + status);
        } finally {
            Arrays.fill(responseBody, (byte) 0);
        }
    }

    /** The "error" field of an error response, or null when there is none. */
    private String errorCode(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            return error != null && error.isString() ? error.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    // ---- JSON ---------------------------------------------------------------------------------------

    /** Parses a response body. The parser's error is dropped: it can quote parts of the body. */
    private JsonNode parse(byte[] body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException e) {
            throw new QuantumLockResponseException("response is not valid JSON");
        }
        if (node == null || node.isMissingNode()) {
            throw new QuantumLockResponseException("response is empty");
        }
        return node;
    }

    private static KeyPairInfo keyPair(JsonNode node) {
        if (!node.isObject()) {
            throw new QuantumLockResponseException("key pair is not a JSON object");
        }
        Instant createdAt;
        try {
            createdAt = OffsetDateTime.parse(text(node, "createdAt")).toInstant();
        } catch (DateTimeParseException e) {
            throw new QuantumLockResponseException("response field createdAt is not a time");
        }
        return new KeyPairInfo(uuid(node, "keyPairId"), Algorithm.fromApiName(text(node, "algorithm")),
                KeyStatus.fromApiName(text(node, "status")), base64(node, "publicKey"), createdAt);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new QuantumLockResponseException("response field " + field + " is missing");
        }
        return value.asString();
    }

    private static UUID uuid(JsonNode node, String field) {
        String text = text(node, field);
        try {
            UUID id = UUID.fromString(text);
            // UUID.fromString also accepts short forms such as "1-2-3-4-5"; only the canonical form is valid here.
            if (!id.toString().equals(text.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new QuantumLockResponseException("response field " + field + " is not an id");
        }
    }

    private static byte[] base64(JsonNode node, String field) {
        try {
            return Base64.getDecoder().decode(text(node, field));
        } catch (IllegalArgumentException e) {
            throw new QuantumLockResponseException("response field " + field + " is not Base64");
        }
    }

    private static void requireSameId(UUID requested, UUID answered) {
        if (!requested.equals(answered)) {
            throw new QuantumLockResponseException("response is about a different key pair");
        }
    }

    // ---- Settings -----------------------------------------------------------------------------------

    /** The API's base URL without a trailing slash. Error messages never repeat the value. */
    private static String checkedBaseUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("The QuantumLock API URL is required");
        }
        URI uri;
        try {
            uri = new URI(apiUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The QuantumLock API URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("The QuantumLock API URL must start with https:// or http://");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "The QuantumLock API URL must be scheme, host, optional port and optional path only");
        }
        if (scheme.equals("http") && !LOCAL_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The QuantumLock API URL must use https:// unless the API runs on "
                    + "this computer (localhost, 127.0.0.1 or ::1)");
        }
        String url = scheme + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Error messages never repeat the value, which may be a real key with a typo. */
    private static String checkedApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("The QuantumLock API key is required");
        }
        String key = apiKey.strip();
        if (!API_KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "The QuantumLock API key has the wrong format (expected ql_test_ or ql_live_ and 43 characters)");
        }
        return key;
    }

    private static Duration positive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be more than zero");
        }
        return duration;
    }

    /** Settings for a {@link QuantumLockClient}. The API URL and API key are required. */
    public static final class Builder {

        private String apiUrl;
        private String apiKey;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder() {
        }

        /** Base URL of the API, for example {@code https://quantumlock.example.com} or {@code http://127.0.0.1:8080}. */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        /** The API key, {@code ql_test_...} or {@code ql_live_...}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** How long to wait for the connection to open. Default 5 seconds. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** How long a whole call may take, until the response starts arriving. Default 30 seconds. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Checks the settings and makes the client. Throws {@link IllegalArgumentException} for a bad setting. */
        public QuantumLockClient build() {
            return new QuantumLockClient(this);
        }

        @Override
        public String toString() {
            return "QuantumLockClient.Builder[apiKey=hidden]";
        }
    }
}
