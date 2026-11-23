package com.quantumlock.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every call of the client against a fake API: what is sent, what is accepted and what is refused. */
class QuantumLockClientTest {

    static final String API_KEY = "ql_test_" + "A".repeat(43);
    static final UUID ID = UUID.fromString("0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11");
    static final byte[] PUBLIC_KEY = bytes(1590, 7);
    static final byte[] CIPHERTEXT = bytes(1568, 3);
    static final byte[] SECRET = bytes(32, 9);

    FakeApi api;
    QuantumLockClient client;

    @BeforeEach
    void start() throws Exception {
        api = new FakeApi();
        client = QuantumLockClient.builder().apiUrl(api.url()).apiKey(API_KEY)
                .requestTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stop() {
        client.close();
        api.close();
    }

    // ---- What is sent ----------------------------------------------------------------------------

    @Test
    void generateSendsTheAlgorithmAndTheKeyInTheHeaderOnly() {
        api.respond(201, keyPairJson(ID, "ML-KEM-1024", "active"));

        KeyPairInfo keyPair = client.generateKeyPair(Algorithm.ML_KEM_1024);

        FakeApi.Request request = api.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/keys/generate");
        assertThat(request.rawQuery()).isNull();
        assertThat(request.header("X-api-key")).isEqualTo(API_KEY);
        assertThat(request.header("Content-type")).isEqualTo("application/json");
        assertThat(request.header("Accept")).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo("{\"algorithm\":\"ML-KEM-1024\"}");
        assertThat(keyPair.keyPairId()).isEqualTo(ID);
        assertThat(keyPair.algorithm()).isEqualTo(Algorithm.ML_KEM_1024);
        assertThat(keyPair.status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(keyPair.publicKey()).isEqualTo(PUBLIC_KEY);
        assertThat(keyPair.createdAt()).isEqualTo(Instant.parse("2026-11-22T10:15:30.123456Z"));
    }

    @Test
    void generateMlDsa() {
        api.respond(201, keyPairJson(ID, "ML-DSA-65", "active"));
        assertThat(client.generateKeyPair(Algorithm.ML_DSA_65).algorithm()).isEqualTo(Algorithm.ML_DSA_65);
        assertThat(api.onlyRequest().body()).isEqualTo("{\"algorithm\":\"ML-DSA-65\"}");
    }

    @Test
    void listReturnsEveryKeyPairInOrder() {
        UUID second = UUID.randomUUID();
        api.respond(200, "[" + keyPairJson(ID, "ML-KEM-1024", "active") + ","
                + keyPairJson(second, "ML-DSA-65", "revoked") + "]");

        List<KeyPairInfo> keyPairs = client.listKeyPairs();

        assertThat(api.onlyRequest().method()).isEqualTo("GET");
        assertThat(api.onlyRequest().path()).isEqualTo("/api/v1/keys");
        assertThat(api.onlyRequest().header("X-api-key")).isEqualTo(API_KEY);
        assertThat(keyPairs).extracting(KeyPairInfo::keyPairId).containsExactly(ID, second);
        assertThat(keyPairs.get(1).status()).isEqualTo(KeyStatus.REVOKED);
    }

    @Test
    void emptyList() {
        api.respond(200, "[]");
        assertThat(client.listKeyPairs()).isEmpty();
    }

    @Test
    void getAsksForTheId() {
        api.respond(200, keyPairJson(ID, "ML-KEM-1024", "rotated"));
        assertThat(client.getKeyPair(ID).status()).isEqualTo(KeyStatus.ROTATED);
        assertThat(api.onlyRequest().path()).isEqualTo("/api/v1/keys/" + ID);
    }

    @Test
    void encapsulateReturnsCiphertextAndSecret() {
        api.respond(200, encapsulateJson(ID, CIPHERTEXT, SECRET));

        try (Encapsulation result = client.encapsulate(ID)) {
            assertThat(api.onlyRequest().method()).isEqualTo("POST");
            assertThat(api.onlyRequest().path()).isEqualTo("/api/v1/keys/" + ID + "/encapsulate");
            assertThat(api.onlyRequest().body()).isEmpty();
            assertThat(result.keyPairId()).isEqualTo(ID);
            assertThat(result.ciphertext()).isEqualTo(CIPHERTEXT);
            assertThat(result.sharedSecret().copyBytes()).isEqualTo(SECRET);
        }
    }

    @Test
    void closingTheEncapsulationDestroysTheSecret() {
        api.respond(200, encapsulateJson(ID, CIPHERTEXT, SECRET));
        Encapsulation result = client.encapsulate(ID);
        result.close();
        assertThat(result.sharedSecret().isDestroyed()).isTrue();
    }

    @Test
    void decapsulateSendsTheCiphertextAsBase64() {
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"sharedSecret\":\"" + b64(SECRET) + "\"}");

        try (SharedSecret secret = client.decapsulate(ID, CIPHERTEXT)) {
            assertThat(api.onlyRequest().path()).isEqualTo("/api/v1/keys/" + ID + "/decapsulate");
            assertThat(api.onlyRequest().header("Content-type")).isEqualTo("application/json");
            assertThat(api.onlyRequest().body()).isEqualTo("{\"ciphertext\":\"" + b64(CIPHERTEXT) + "\"}");
            assertThat(secret.copyBytes()).isEqualTo(SECRET);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1567, 1569})
    void decapsulateRefusesAWrongLengthCiphertextWithoutCallingTheApi(int length) {
        assertThatThrownBy(() -> client.decapsulate(ID, new byte[length]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(api.requests()).isEmpty();
    }

    @Test
    void nullArgumentsAreRefusedWithoutCallingTheApi() {
        assertThatThrownBy(() -> client.generateKeyPair(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.getKeyPair(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.encapsulate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.decapsulate(ID, null)).isInstanceOf(NullPointerException.class);
        assertThat(api.requests()).isEmpty();
    }

    @Test
    void aBasePathIsKept() throws Exception {
        api.respond(200, "[]");
        try (QuantumLockClient behindProxy = QuantumLockClient.builder().apiUrl(api.url() + "/quantumlock/")
                .apiKey(API_KEY).build()) {
            behindProxy.listKeyPairs();
        }
        assertThat(api.onlyRequest().path()).isEqualTo("/quantumlock/api/v1/keys");
    }

    // ---- API errors ------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "401, unauthorized",
            "404, key_not_found",
            "400, wrong_key_type",
            "400, key_not_active",
            "413, payload_too_large",
            "429, rate_limited",
            "500, operation_failed",
            "503, service_unavailable",
    })
    void apiErrorsBecomeTypedExceptionsWithTheCode(int status, String code) {
        api.respond(status, "{\"error\":\"" + code + "\"}");

        QuantumLockApiException e = catchThrowableOfType(QuantumLockApiException.class, () -> client.encapsulate(ID));

        assertThat(e.status()).isEqualTo(status);
        assertThat(e.errorCode()).isEqualTo(code);
        assertThat(e.getMessage()).isEqualTo("QuantumLock API error " + status + " " + code);
        assertThat(e.retryAfterSeconds()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "60, 60",
            "1, 1",
            "'Wed, 21 Oct 2015 07:28:00 GMT', -1",
            "-5, -1",
            "1234567, -1",
    })
    void aRateLimitedCallSaysHowLongToWait(String header, long expected) {
        api.respond(request -> new FakeApi.Answer(429, "{\"error\":\"rate_limited\"}",
                Map.of("Content-Type", "application/json", "Retry-After", header), 0));

        QuantumLockApiException e = catchThrowableOfType(QuantumLockApiException.class, () -> client.listKeyPairs());

        assertThat(e.status()).isEqualTo(429);
        assertThat(e.errorCode()).isEqualTo("rate_limited");
        if (expected < 0) {
            assertThat(e.retryAfterSeconds()).isEmpty();
        } else {
            assertThat(e.retryAfterSeconds()).hasValue(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json at all",
            "{\"error\":\"<script>alert(1)</script>\"}",
            "{\"error\":\"Key-Not-Found\"}",
            "{\"error\":42}",
            "{\"message\":\"secret detail\"}",
    })
    void anUnreadableErrorBodyGivesUnknownErrorAndIsNeverCopied(String body) {
        api.respond(500, body);

        QuantumLockApiException e = catchThrowableOfType(QuantumLockApiException.class, () -> client.listKeyPairs());

        assertThat(e.errorCode()).isEqualTo(QuantumLockApiException.UNKNOWN_ERROR);
        assertThat(e.getMessage()).isEqualTo("QuantumLock API error 500 unknown_error");
    }

    // ---- Refused responses -----------------------------------------------------------------------

    @Test
    void redirectsAreNotFollowed() throws Exception {
        try (FakeApi elsewhere = new FakeApi()) {
            elsewhere.respond(200, "[]");
            api.respond(request -> new FakeApi.Answer(302, "", Map.of("Location", elsewhere.url() + "/api/v1/keys"), 0));

            assertThatThrownBy(() -> client.listKeyPairs())
                    .isInstanceOf(QuantumLockResponseException.class)
                    .hasMessage("unexpected HTTP status 302");
            assertThat(elsewhere.requests()).as("the API key must not reach another server").isEmpty();
        }
    }

    @Test
    void anUnexpectedSuccessStatusIsRefused() {
        api.respond(200, keyPairJson(ID, "ML-KEM-1024", "active"));
        assertThatThrownBy(() -> client.generateKeyPair(Algorithm.ML_KEM_1024))
                .isInstanceOf(QuantumLockResponseException.class)
                .hasMessage("unexpected HTTP status 200");
    }

    @Test
    void anEncapsulationForAnotherKeyPairIsRefused() {
        api.respond(200, encapsulateJson(UUID.randomUUID(), CIPHERTEXT, SECRET));
        assertThatThrownBy(() -> client.encapsulate(ID)).isInstanceOf(QuantumLockResponseException.class)
                .hasMessage("response is about a different key pair");
    }

    @Test
    void aDecapsulationForAnotherKeyPairIsRefused() {
        api.respond(200, "{\"keyPairId\":\"" + UUID.randomUUID() + "\",\"sharedSecret\":\"" + b64(SECRET) + "\"}");
        assertThatThrownBy(() -> client.decapsulate(ID, CIPHERTEXT)).isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void aKeyPairOtherThanTheOneAskedForIsRefused() {
        api.respond(200, keyPairJson(UUID.randomUUID(), "ML-KEM-1024", "active"));
        assertThatThrownBy(() -> client.getKeyPair(ID)).isInstanceOf(QuantumLockResponseException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16, 31, 33, 64})
    void aSharedSecretOfTheWrongLengthIsRefused(int length) {
        api.respond(200, encapsulateJson(ID, CIPHERTEXT, new byte[length]));
        assertThatThrownBy(() -> client.encapsulate(ID)).isInstanceOf(QuantumLockResponseException.class)
                .hasMessage("shared secret has the wrong length");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1088, 1567, 1569})
    void aCiphertextOfTheWrongLengthIsRefused(int length) {
        api.respond(200, encapsulateJson(ID, new byte[length], SECRET));
        assertThatThrownBy(() -> client.encapsulate(ID)).isInstanceOf(QuantumLockResponseException.class)
                .hasMessage("ciphertext has the wrong length");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"keyPairId\":\"" + "0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11" + "\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"ciphertext\":\"%%%\",\"sharedSecret\":\"AA==\"}",
            "{\"keyPairId\":\"1-2-3-4-5\",\"ciphertext\":\"\",\"sharedSecret\":\"\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\"}",
            "[]",
    })
    void malformedEncapsulateResponsesAreRefused(String body) {
        api.respond(200, body);
        assertThatThrownBy(() -> client.encapsulate(ID)).isInstanceOf(QuantumLockResponseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "[{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\"}]",
            "[" + "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"algorithm\":\"RSA-2048\",\"status\":\"active\",\"publicKey\":\"AA==\",\"createdAt\":\"2026-11-22T10:15:30Z\"}" + "]",
            "[" + "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"algorithm\":\"ML-KEM-1024\",\"status\":\"lost\",\"publicKey\":\"AA==\",\"createdAt\":\"2026-11-22T10:15:30Z\"}" + "]",
            "[" + "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"algorithm\":\"ML-KEM-1024\",\"status\":\"active\",\"publicKey\":\"AA==\",\"createdAt\":\"yesterday\"}" + "]",
            "[" + "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"algorithm\":\"ML-KEM-1024\",\"status\":\"active\",\"publicKey\":7,\"createdAt\":\"2026-11-22T10:15:30Z\"}" + "]",
            "[1]",
    })
    void malformedListResponsesAreRefused(String body) {
        api.respond(200, body);
        assertThatThrownBy(() -> client.listKeyPairs()).isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void aMalformedResponseNeverCarriesTheBody() {
        String secretLooking = "SECRET" + b64(SECRET);
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"sharedSecret\":" + secretLooking + "}");

        QuantumLockResponseException e = catchThrowableOfType(QuantumLockResponseException.class, () -> client.encapsulate(ID));

        assertThat(e.getMessage()).doesNotContain(secretLooking).doesNotContain(b64(SECRET));
        assertThat(e.getCause()).isNull();
    }

    @Test
    void aResponseOverTheSizeLimitIsRefused() {
        api.respond(200, "[" + " ".repeat(QuantumLockClient.MAX_RESPONSE_BYTES) + "]");
        assertThatThrownBy(() -> client.listKeyPairs()).isInstanceOf(QuantumLockResponseException.class)
                .hasMessageContaining("larger than");
    }

    // ---- Transport -------------------------------------------------------------------------------

    @Test
    void aSlowApiTimesOut() {
        api.respond(request -> new FakeApi.Answer(200, "[]", Map.of(), 4_000));
        assertThatThrownBy(() -> client.listKeyPairs()).isInstanceOf(QuantumLockTransportException.class);
    }

    @Test
    void anApiThatIsNotRunningGivesATransportError() throws Exception {
        String url = api.url();
        api.close();
        try (QuantumLockClient offline = QuantumLockClient.builder().apiUrl(url).apiKey(API_KEY).build()) {
            assertThatThrownBy(offline::listKeyPairs).isInstanceOf(QuantumLockTransportException.class)
                    .hasMessage("could not reach the QuantumLock API");
        }
    }

    @Test
    void anInterruptedCallKeepsTheInterruptFlag() {
        api.respond(request -> new FakeApi.Answer(200, "[]", Map.of(), 1_000));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.listKeyPairs()).isInstanceOf(QuantumLockTransportException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ---- Signatures --------------------------------------------------------------------------------

    static final byte[] SIGNATURE = bytes(3309, 5);
    static final byte[] MESSAGE = "Invoice 2026-118".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final byte[] CONTEXT = "invoices".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void signSendsMessageAndContextAsBase64() throws Exception {
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"signature\":\"" + b64(SIGNATURE) + "\"}");

        byte[] signature = client.sign(ID, MESSAGE, CONTEXT);

        FakeApi.Request request = api.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/keys/" + ID + "/sign");
        assertThat(request.header("X-api-key")).isEqualTo(API_KEY);
        Map<?, ?> sent = new tools.jackson.databind.json.JsonMapper().readValue(request.body(), Map.class);
        assertThat(sent).isEqualTo(Map.of("message", b64(MESSAGE), "context", b64(CONTEXT)));
        assertThat(signature).isEqualTo(SIGNATURE);
    }

    @Test
    void signWithoutAContextSendsAnEmptyOne() throws Exception {
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"signature\":\"" + b64(SIGNATURE) + "\"}");
        client.sign(ID, MESSAGE);
        Map<?, ?> sent = new tools.jackson.databind.json.JsonMapper().readValue(api.onlyRequest().body(), Map.class);
        assertThat(sent).isEqualTo(Map.of("message", b64(MESSAGE), "context", ""));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,false"})
    void verifySendsEverythingAndReturnsTheAnswer(String answer, boolean expected) throws Exception {
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"valid\":" + answer + "}");

        assertThat(client.verify(ID, MESSAGE, SIGNATURE, CONTEXT)).isEqualTo(expected);

        FakeApi.Request request = api.onlyRequest();
        assertThat(request.path()).isEqualTo("/api/v1/keys/" + ID + "/verify");
        Map<?, ?> sent = new tools.jackson.databind.json.JsonMapper().readValue(request.body(), Map.class);
        assertThat(sent).isEqualTo(Map.of("message", b64(MESSAGE), "signature", b64(SIGNATURE),
                "context", b64(CONTEXT)));
    }

    @Test
    void badSignatureInputsAreRefusedWithoutCallingTheApi() {
        assertThatThrownBy(() -> client.sign(null, MESSAGE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.sign(ID, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.sign(ID, MESSAGE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.sign(ID, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sign(ID, new byte[QuantumLockClient.MAX_MESSAGE_LENGTH + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sign(ID, MESSAGE, new byte[QuantumLockClient.MAX_CONTEXT_LENGTH + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.verify(ID, MESSAGE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.verify(ID, MESSAGE, new byte[3308]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.verify(ID, MESSAGE, new byte[3310]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.verify(ID, new byte[0], SIGNATURE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(api.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"signature\":\"AAAA\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"signature\":\"%%%\"}",
            "{\"keyPairId\":\"ffffffff-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"signature\":\"\"}",
            "[]",
    })
    void malformedSignResponsesAreRefused(String body) {
        api.respond(200, body);
        assertThatThrownBy(() -> client.sign(ID, MESSAGE)).isInstanceOf(QuantumLockResponseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"valid\":\"true\"}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"valid\":1}",
            "{\"keyPairId\":\"0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"valid\":null}",
            "{\"keyPairId\":\"ffffffff-3f4e-4c1a-9a51-6d2b8f0c7e11\",\"valid\":true}",
    })
    void malformedVerifyResponsesAreRefused(String body) {
        api.respond(200, body);
        assertThatThrownBy(() -> client.verify(ID, MESSAGE, SIGNATURE)).isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void aSignatureOfTheWrongLengthIsRefused() {
        api.respond(200, "{\"keyPairId\":\"" + ID + "\",\"signature\":\"" + b64(new byte[3308]) + "\"}");
        assertThatThrownBy(() -> client.sign(ID, MESSAGE)).isInstanceOf(QuantumLockResponseException.class)
                .hasMessage("signature has the wrong length");
    }

    @Test
    void signatureErrorsBecomeTypedExceptions() {
        api.respond(400, "{\"error\":\"wrong_key_type\"}");
        QuantumLockApiException e = catchThrowableOfType(QuantumLockApiException.class, () -> client.sign(ID, MESSAGE));
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.errorCode()).isEqualTo("wrong_key_type");
    }

    // ---- Nothing secret is shown -----------------------------------------------------------------

    @Test
    void theApiKeyAndSecretsNeverAppearInToString() {
        api.respond(200, encapsulateJson(ID, CIPHERTEXT, SECRET));
        Encapsulation result = client.encapsulate(ID);

        assertThat(client.toString()).doesNotContain(API_KEY).contains("apiKey=hidden");
        assertThat(QuantumLockClient.builder().apiKey(API_KEY).toString()).doesNotContain(API_KEY);
        assertThat(result.toString()).doesNotContain(b64(SECRET)).contains("hidden");
        assertThat(result.sharedSecret().toString()).isEqualTo("SharedSecret[hidden]");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    static String keyPairJson(UUID id, String algorithm, String status) {
        return "{\"keyPairId\":\"" + id + "\",\"algorithm\":\"" + algorithm + "\",\"status\":\"" + status
                + "\",\"publicKey\":\"" + b64(PUBLIC_KEY) + "\",\"createdAt\":\"2026-11-22T10:15:30.123456Z\"}";
    }

    static String encapsulateJson(UUID id, byte[] ciphertext, byte[] secret) {
        return "{\"keyPairId\":\"" + id + "\",\"ciphertext\":\"" + b64(ciphertext) + "\",\"sharedSecret\":\""
                + b64(secret) + "\"}";
    }

    static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }
}
