package com.quantumlock.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The audit log calls (KAN-32) against a fake API: the query sent, the page read and every refused answer. */
class AuditLogClientTest {

    static final String API_KEY = "ql_test_" + "A".repeat(43);
    static final UUID KEY_PAIR = UUID.fromString("0b5c1f64-3f4e-4c1a-9a51-6d2b8f0c7e11");
    static final UUID API_KEY_ID = UUID.fromString("7d3e2a10-5b6c-4f8e-9a1b-2c3d4e5f6a7b");

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

    @Test
    void theDefaultCallAsksForTheNewest50AndReadsEveryField() {
        api.respond(200, page(null, entry("12", "sign", "success", KEY_PAIR), entry("9", "encapsulate", "failure", null)));

        AuditLogPage page = client.auditLog();

        FakeApi.Request request = api.onlyRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/api/v1/audit-logs");
        assertThat(request.rawQuery()).isEqualTo("limit=50");
        assertThat(request.header("X-api-key")).isEqualTo(API_KEY);
        assertThat(page.entries()).hasSize(2);
        assertThat(page.nextBefore()).isEmpty();
        AuditLogEntry first = page.entries().get(0);
        assertThat(first.id()).isEqualTo("12");
        assertThat(first.time()).isEqualTo(Instant.parse("2026-11-23T10:15:30.123456Z"));
        assertThat(first.action()).isEqualTo("sign");
        assertThat(first.success()).isTrue();
        assertThat(first.keyPairId()).contains(KEY_PAIR);
        assertThat(first.apiKeyId()).contains(API_KEY_ID);
        AuditLogEntry second = page.entries().get(1);
        assertThat(second.success()).isFalse();
        assertThat(second.keyPairId()).isEmpty();
    }

    @Test
    void everyFilterIsSent() {
        api.respond(200, page("12", entry("12", "sign", "failure", KEY_PAIR)));

        AuditLogPage page = client.auditLog(AuditLogQuery.newest().keyPair(KEY_PAIR).action("sign").success(false)
                .limit(1).before("40"));

        assertThat(api.onlyRequest().rawQuery())
                .isEqualTo("limit=1&keyPairId=" + KEY_PAIR + "&action=sign&status=failure&before=40");
        assertThat(page.nextBefore()).contains("12");
    }

    @Test
    void anEmptyLogIsAnEmptyPage() {
        api.respond(200, "{\"entries\":[],\"nextBefore\":null}");
        AuditLogPage page = client.auditLog();
        assertThat(page.entries()).isEmpty();
        assertThat(page.nextBefore()).isEmpty();
    }

    @Test
    void theQueryIsImmutable() {
        AuditLogQuery base = AuditLogQuery.newest();
        AuditLogQuery narrowed = base.action("sign").limit(5);
        assertThat(base.action()).isEmpty();
        assertThat(base.limit()).isEqualTo(50);
        assertThat(narrowed.action()).contains("sign");
        assertThat(narrowed.limit()).isEqualTo(5);
    }

    @Test
    void badQueryValuesAreRefusedBeforeAnythingIsSent() {
        AuditLogQuery query = AuditLogQuery.newest();
        assertThatThrownBy(() -> query.limit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.limit(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.action("Sign")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.action("sign&limit=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.before("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.before("12&x=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.keyPair(null)).isInstanceOf(NullPointerException.class);
        assertThat(api.requests()).isEmpty();
    }

    @Test
    void anApiErrorKeepsItsCode() {
        api.respond(400, "{\"error\":\"invalid_request\"}");
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().before("5")))
                .isInstanceOfSatisfying(QuantumLockApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("invalid_request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{}",
            "{\"entries\":{}}",
            "{\"entries\":[42]}",
            // Missing or wrong fields
            "{\"entries\":[{\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"sign\",\"status\":\"success\"}]}",
            "{\"entries\":[{\"id\":\"x1\",\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"sign\",\"status\":\"success\"}]}",
            "{\"entries\":[{\"id\":\"1\",\"time\":\"yesterday\",\"action\":\"sign\",\"status\":\"success\"}]}",
            "{\"entries\":[{\"id\":\"1\",\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"<b>\",\"status\":\"success\"}]}",
            "{\"entries\":[{\"id\":\"1\",\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"sign\",\"status\":\"ok\"}]}",
            "{\"entries\":[{\"id\":\"1\",\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"sign\",\"status\":\"success\",\"keyPairId\":\"1-2-3-4-5\"}]}",
            // A cursor on a page that is not full, or not the last row's id
            "{\"entries\":[{\"id\":\"1\",\"time\":\"2026-11-23T10:15:30Z\",\"action\":\"sign\",\"status\":\"success\"}],\"nextBefore\":\"1\"}",
    })
    void malformedPagesAreRefused(String body) {
        api.respond(200, body);
        assertThatThrownBy(() -> client.auditLog()).isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void aCursorThatIsNotTheLastRowIsRefused() {
        api.respond(200, page("11", entry("12", "sign", "success", null)));
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().limit(1)))
                .isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void moreRowsThanAskedForAreRefused() {
        api.respond(200, page(null, entry("12", "sign", "success", null), entry("11", "sign", "success", null)));
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().limit(1)))
                .isInstanceOf(QuantumLockResponseException.class);
    }

    @Test
    void rowsThatDoNotMatchTheFiltersAreRefused() {
        api.respond(200, page(null, entry("12", "verify", "success", KEY_PAIR)));
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().action("sign")))
                .isInstanceOf(QuantumLockResponseException.class);

        api.respond(200, page(null, entry("12", "sign", "success", UUID.randomUUID())));
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().keyPair(KEY_PAIR)))
                .isInstanceOf(QuantumLockResponseException.class);

        api.respond(200, page(null, entry("12", "sign", "success", null)));
        assertThatThrownBy(() -> client.auditLog(AuditLogQuery.newest().success(false)))
                .isInstanceOf(QuantumLockResponseException.class);
    }

    private static String entry(String id, String action, String status, UUID keyPair) {
        return "{\"id\":\"" + id + "\",\"time\":\"2026-11-23T10:15:30.123456Z\",\"action\":\"" + action
                + "\",\"status\":\"" + status + "\",\"keyPairId\":" + (keyPair == null ? "null" : "\"" + keyPair + "\"")
                + ",\"apiKeyId\":\"" + API_KEY_ID + "\"}";
    }

    private static String page(String nextBefore, String... entries) {
        return "{\"entries\":[" + String.join(",", entries) + "],\"nextBefore\":"
                + (nextBefore == null ? "null" : "\"" + nextBefore + "\"") + "}";
    }
}
