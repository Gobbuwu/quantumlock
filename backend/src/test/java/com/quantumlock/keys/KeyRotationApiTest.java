package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.IssuedApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Key rotation and revocation (KAN-30) through HTTP: what each state allows, the grace period, input checks,
 * two rotations at once, team isolation and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KeyRotationApiTest {

    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");
    private static final String MESSAGE = Base64.getEncoder()
            .encodeToString("Invoice 2026-118".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private JdbcTemplate jdbc;

    private String demoKey;
    private UUID demoKeyId;

    @BeforeEach
    void issueApiKey() {
        IssuedApiKey issued = apiKeys.issue(DEMO_ACCOUNT, "test");
        demoKey = issued.key();
        demoKeyId = issued.entity().getApiKeyId();
    }

    // ---- Rotation ---------------------------------------------------------------------------------

    @Test
    void rotationGivesANewKeyPairAndLinksTheOldOneToIt() throws Exception {
        String old = generate("ML-KEM-1024");
        String oldBefore = getKey(old);

        String created = rotate(old, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replacement = JsonPath.read(created, "$.keyPairId");

        assertNotEquals(old, replacement);
        assertEquals("ML-KEM-1024", JsonPath.read(created, "$.algorithm"));
        assertEquals("active", JsonPath.read(created, "$.status"));
        assertNull(JsonPath.read(created, "$.replacedBy"));
        assertNotEquals((String) JsonPath.read(oldBefore, "$.publicKey"), JsonPath.read(created, "$.publicKey"));

        String oldAfter = getKey(old);
        assertEquals("rotated", JsonPath.read(oldAfter, "$.status"));
        assertEquals(replacement, JsonPath.read(oldAfter, "$.replacedBy"));
        Instant rotatedAt = Instant.parse(JsonPath.read(oldAfter, "$.rotatedAt"));
        Instant endsAt = Instant.parse(JsonPath.read(oldAfter, "$.gracePeriodEndsAt"));
        assertEquals(Duration.ofDays(7), Duration.between(rotatedAt, endsAt), "the default grace period");
        assertEquals(JsonPath.read(created, "$.createdAt"), rotatedAt.toString());
        assertEquals((String) JsonPath.read(oldBefore, "$.publicKey"), JsonPath.read(oldAfter, "$.publicKey"));
    }

    @Test
    void theGracePeriodCanBeChosen() throws Exception {
        for (int days : new int[] {0, 1, 90}) {
            String old = generate("ML-DSA-65");
            rotate(old, "{\"gracePeriodDays\":" + days + "}").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.algorithm").value("ML-DSA-65"));
            String after = getKey(old);
            assertEquals(Duration.ofDays(days), Duration.between(Instant.parse(JsonPath.read(after, "$.rotatedAt")),
                    Instant.parse(JsonPath.read(after, "$.gracePeriodEndsAt"))));
        }
        String old = generate("ML-KEM-1024");
        rotate(old, "{}").andExpect(status().isCreated());
        String after = getKey(old);
        assertEquals(Duration.ofDays(7), Duration.between(Instant.parse(JsonPath.read(after, "$.rotatedAt")),
                Instant.parse(JsonPath.read(after, "$.gracePeriodEndsAt"))));
    }

    @Test
    void aBadGracePeriodGetsOneFixedAnswerAndChangesNothing() throws Exception {
        String old = generate("ML-KEM-1024");
        int keyPairsBefore = keyPairCount();
        String[] bodies = {
            "{\"gracePeriodDays\":-1}",
            "{\"gracePeriodDays\":91}",
            "{\"gracePeriodDays\":2147483648}",
            "{\"gracePeriodDays\":7.5}",
            "{\"gracePeriodDays\":7.0}",
            "{\"gracePeriodDays\":\"7\"}",
            "{\"gracePeriodDays\":true}",
            "[]",
            "not json",
        };
        for (String body : bodies) {
            rotate(old, body).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"invalid_request\"}", true));
        }
        assertEquals("active", JsonPath.read(getKey(old), "$.status"));
        assertEquals(keyPairsBefore, keyPairCount(), "no replacement was saved");
    }

    // ---- What each state allows -------------------------------------------------------------------

    @Test
    void aRotatedKeyExchangeKeyStillDecapsulatesDuringItsGracePeriodOnly() throws Exception {
        String old = generate("ML-KEM-1024");
        String encapsulated = call(old, "encapsulate", null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String ciphertext = JsonPath.read(encapsulated, "$.ciphertext");
        rotate(old, null).andExpect(status().isCreated());

        // Data sent to the old key before the rotation can still be read...
        call(old, "decapsulate", "{\"ciphertext\":\"" + ciphertext + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedSecret").value((String) JsonPath.read(encapsulated, "$.sharedSecret")));
        // ...but nothing new can be sent to it, and it cannot be rotated again.
        expectNotActive(call(old, "encapsulate", null));
        expectNotActive(rotate(old, null));

        endGracePeriod(old);
        expectNotActive(call(old, "decapsulate", "{\"ciphertext\":\"" + ciphertext + "\"}"));
        expectNotActive(call(old, "encapsulate", null));
        expectNotActive(rotate(old, null));
    }

    @Test
    void aRotatedSigningKeyStillVerifiesDuringItsGracePeriodOnly() throws Exception {
        String old = generate("ML-DSA-65");
        String signature = JsonPath.read(call(old, "sign", "{\"message\":\"" + MESSAGE + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.signature");
        String verifyBody = "{\"message\":\"" + MESSAGE + "\",\"signature\":\"" + signature + "\"}";
        rotate(old, null).andExpect(status().isCreated());

        call(old, "verify", verifyBody).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        expectNotActive(call(old, "sign", "{\"message\":\"" + MESSAGE + "\"}"));

        endGracePeriod(old);
        expectNotActive(call(old, "verify", verifyBody));
    }

    @Test
    void aGracePeriodOfZeroEndsTheOldKeysUseAtOnce() throws Exception {
        String old = generate("ML-KEM-1024");
        String ciphertext = JsonPath.read(call(old, "encapsulate", null).andReturn().getResponse()
                .getContentAsString(), "$.ciphertext");
        rotate(old, "{\"gracePeriodDays\":0}").andExpect(status().isCreated());

        expectNotActive(call(old, "decapsulate", "{\"ciphertext\":\"" + ciphertext + "\"}"));
    }

    @Test
    void theReplacementWorksLikeAnyNewKeyPair() throws Exception {
        String old = generate("ML-DSA-65");
        String replacement = JsonPath.read(rotate(old, null).andReturn().getResponse().getContentAsString(),
                "$.keyPairId");

        String signature = JsonPath.read(call(replacement, "sign", "{\"message\":\"" + MESSAGE + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.signature");
        String verifyBody = "{\"message\":\"" + MESSAGE + "\",\"signature\":\"" + signature + "\"}";
        call(replacement, "verify", verifyBody).andExpect(jsonPath("$.valid").value(true));
        // A signature by the new key does not verify under the old one.
        call(old, "verify", verifyBody).andExpect(jsonPath("$.valid").value(false));
        // The replacement can itself be rotated.
        rotate(replacement, null).andExpect(status().isCreated());
    }

    // ---- Revocation -------------------------------------------------------------------------------

    @Test
    void revocationStopsAnActiveKeyForGood() throws Exception {
        String id = generate("ML-KEM-1024");
        String ciphertext = JsonPath.read(call(id, "encapsulate", null).andReturn().getResponse()
                .getContentAsString(), "$.ciphertext");

        revoke(id).andExpect(status().isOk()).andExpect(jsonPath("$.keyPairId").value(id))
                .andExpect(jsonPath("$.status").value("revoked"));

        expectNotActive(call(id, "encapsulate", null));
        expectNotActive(call(id, "decapsulate", "{\"ciphertext\":\"" + ciphertext + "\"}"));
        expectNotActive(rotate(id, null));
        expectNotActive(revoke(id));
        assertEquals("revoked", JsonPath.read(getKey(id), "$.status"), "the row is kept");
    }

    @Test
    void revokingARotatedKeyEndsItsGracePeriodAndLeavesTheReplacementAlone() throws Exception {
        String old = generate("ML-DSA-65");
        String signature = JsonPath.read(call(old, "sign", "{\"message\":\"" + MESSAGE + "\"}").andReturn()
                .getResponse().getContentAsString(), "$.signature");
        String replacement = JsonPath.read(rotate(old, null).andReturn().getResponse().getContentAsString(),
                "$.keyPairId");

        revoke(old).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("revoked"))
                .andExpect(jsonPath("$.replacedBy").value(replacement));

        expectNotActive(call(old, "verify", "{\"message\":\"" + MESSAGE + "\",\"signature\":\"" + signature + "\"}"));
        assertEquals("active", JsonPath.read(getKey(replacement), "$.status"));
        call(replacement, "sign", "{\"message\":\"" + MESSAGE + "\"}").andExpect(status().isOk());

        // A rotated key whose grace period is over can still be revoked, to say so on the record.
        String other = generate("ML-KEM-1024");
        rotate(other, null).andExpect(status().isCreated());
        endGracePeriod(other);
        revoke(other).andExpect(status().isOk());
    }

    // ---- Concurrency, isolation, audit ------------------------------------------------------------

    @Test
    void twoRotationsAtOnceGiveExactlyOneReplacement() throws Exception {
        int keyPairsBefore = keyPairCount();
        for (int round = 0; round < 5; round++) {
            String old = generate("ML-KEM-1024");
            List<Integer> statuses = runTogether(() -> rotate(old, null).andReturn().getResponse().getStatus(),
                    () -> rotate(old, null).andReturn().getResponse().getStatus());

            assertEquals(List.of(201, 400), statuses.stream().sorted().toList(), "round " + round);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM key_pairs WHERE key_pair_id = (SELECT replaced_by"
                    + " FROM key_pairs WHERE key_pair_id = ?)", Integer.class, UUID.fromString(old)));
            assertEquals(List.of("generate:success", "rotate:failure", "rotate:success"),
                    auditLogs.findByKeyPairId(UUID.fromString(old)).stream()
                            .map(e -> e.getAction() + ":" + e.getStatus()).sorted().toList());
        }
        assertEquals(keyPairsBefore + 10, keyPairCount(), "5 old key pairs and 5 replacements, nothing else saved");
    }

    @Test
    void aRotationAndARevocationAtOnceLeaveAConsistentKey() throws Exception {
        for (int round = 0; round < 5; round++) {
            String old = generate("ML-KEM-1024");
            List<Integer> statuses = runTogether(() -> rotate(old, null).andReturn().getResponse().getStatus(),
                    () -> revoke(old).andReturn().getResponse().getStatus());

            // The revocation always wins in the end: either it came second, or it came first and the rotation found
            // the key revoked.
            assertEquals("revoked", JsonPath.read(getKey(old), "$.status"), "round " + round);
            assertTrue(statuses.contains(200), "round " + round);
        }
    }

    @Test
    void anotherTeamsKeyLooksLikeAnUnknownKey() throws Exception {
        String otherKey = apiKeys.issue(OTHER_ACCOUNT, "test").key();
        String theirs = JsonPath.read(mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, otherKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"ML-KEM-1024\"}"))
                .andReturn().getResponse().getContentAsString(), "$.keyPairId");

        String refusals = "SELECT COUNT(*) FROM audit_logs WHERE account_id = ? AND api_key_id = ?"
                + " AND key_pair_id IS NULL AND action IN ('rotate', 'revoke') AND status = 'failure'";
        for (String id : new String[] {theirs, UUID.randomUUID().toString(), "not-a-uuid"}) {
            rotate(id, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("key_not_found"));
            revoke(id).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("key_not_found"));
        }
        assertEquals("active", jdbc.queryForObject("SELECT status FROM key_pairs WHERE key_pair_id = ?",
                String.class, UUID.fromString(theirs)));
        // One refusal each for rotate and revoke for both real ids, recorded without a key pair id. A path that is
        // not an id at all is refused before it reaches the service, as for every other key endpoint.
        assertEquals(4, jdbc.queryForObject(refusals, Integer.class, DEMO_ACCOUNT, demoKeyId));
    }

    @Test
    void withoutAnApiKeyNothingHappens() throws Exception {
        String id = generate("ML-KEM-1024");
        int keyPairsBefore = keyPairCount();
        mockMvc.perform(post("/api/v1/keys/" + id + "/rotate")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/keys/" + id + "/revoke")).andExpect(status().isUnauthorized());
        assertEquals("active", JsonPath.read(getKey(id), "$.status"));
        assertEquals(keyPairsBefore, keyPairCount());
    }

    @Test
    void everyChangeIsAuditedWithTheApiKey() throws Exception {
        String old = generate("ML-KEM-1024");
        String replacement = JsonPath.read(rotate(old, null).andReturn().getResponse().getContentAsString(),
                "$.keyPairId");
        rotate(old, null); // refused: no longer active
        revoke(replacement);

        assertEquals(List.of("generate:success:true", "rotate:failure:true", "rotate:success:true"),
                entries(old));
        assertEquals(List.of("generate:success:true", "revoke:success:true"), entries(replacement));
    }

    @Test
    void theSettingsMustFitTogether() {
        assertEquals(7, new RotationSettings(7, 90).defaultDays());
        assertEquals(0, new RotationSettings(0, 0).maxDays());
        assertEquals(3650, new RotationSettings(3650, 3650).maxDays());
        assertThrows(IllegalStateException.class, () -> new RotationSettings(-1, 90));
        assertThrows(IllegalStateException.class, () -> new RotationSettings(91, 90));
        assertThrows(IllegalStateException.class, () -> new RotationSettings(0, -1));
        assertThrows(IllegalStateException.class, () -> new RotationSettings(7, 3651));
    }

    // ---- helpers ----

    private String generate(String algorithm) throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"" + algorithm + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.keyPairId");
    }

    private String getKey(String id) throws Exception {
        return mockMvc.perform(get("/api/v1/keys/" + id).header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions rotate(String id, String body) throws Exception {
        var request = post("/api/v1/keys/" + id + "/rotate").header(ApiKeys.HEADER, demoKey);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions revoke(String id) throws Exception {
        return mockMvc.perform(post("/api/v1/keys/" + id + "/revoke").header(ApiKeys.HEADER, demoKey));
    }

    private org.springframework.test.web.servlet.ResultActions call(String id, String operation, String body)
            throws Exception {
        var request = post("/api/v1/keys/" + id + "/" + operation).header(ApiKeys.HEADER, demoKey);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private static void expectNotActive(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"key_not_active\"}", true));
    }

    /** Moves the grace period's end into the past, as if it had run out. */
    private void endGracePeriod(String id) {
        jdbc.update("UPDATE key_pairs SET grace_period_ends_at = ? WHERE key_pair_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), UUID.fromString(id));
    }

    private int keyPairCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM key_pairs WHERE account_id = ?", Integer.class, DEMO_ACCOUNT);
    }

    private List<String> entries(String id) {
        return auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                .map(e -> e.getAction() + ":" + e.getStatus() + ":" + demoKeyId.equals(e.getApiKeyId()))
                .sorted().toList();
    }

    /** Starts both calls at the same moment and returns their HTTP statuses. */
    private static List<Integer> runTogether(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
