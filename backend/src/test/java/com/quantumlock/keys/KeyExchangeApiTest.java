package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.KeyGenerator;

import com.jayway.jsonpath.JsonPath;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.IssuedApiKey;
import com.quantumlock.crypto.CryptoProviders;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Encapsulation and decapsulation (task 3.4) through HTTP: round trips with the API and with an independent
 * Bouncy Castle client, FIPS 203 implicit rejection, strict input checks, team isolation and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KeyExchangeApiTest {

    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");

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

    @Test
    void encapsulateThenDecapsulateGivesTheSameSecret() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");

        String encapsulated = encapsulate(demoKey, id);
        byte[] ciphertext = decode(JsonPath.read(encapsulated, "$.ciphertext"));
        byte[] secret = decode(JsonPath.read(encapsulated, "$.sharedSecret"));
        assertEquals(1568, ciphertext.length);
        assertEquals(32, secret.length);
        assertEquals(id, JsonPath.read(encapsulated, "$.keyPairId"));

        assertArrayEquals(secret, decapsulate(demoKey, id, JsonPath.read(encapsulated, "$.ciphertext")));
    }

    @Test
    void aClientCanEncapsulateWithThePublicKeyOnItsOwn() throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"ML-KEM-1024\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.keyPairId");
        PublicKey publicKey = KeyFactory.getInstance("ML-KEM-1024", CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(decode(JsonPath.read(body, "$.publicKey"))));

        // What a sender does with Bouncy Castle and only the published public key.
        KeyGenerator sender = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        sender.init(new KEMGenerateSpec(publicKey, "AES"));
        SecretKeyWithEncapsulation sent = (SecretKeyWithEncapsulation) sender.generateKey();

        byte[] received = decapsulate(demoKey, id, Base64.getEncoder().encodeToString(sent.getEncapsulation()));
        assertArrayEquals(sent.getEncoded(), received);
    }

    @Test
    void everyEncapsulationIsFresh() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String first = encapsulate(demoKey, id);
        String second = encapsulate(demoKey, id);

        assertNotEquals((String) JsonPath.read(first, "$.ciphertext"), JsonPath.read(second, "$.ciphertext"));
        assertNotEquals((String) JsonPath.read(first, "$.sharedSecret"), JsonPath.read(second, "$.sharedSecret"));
    }

    @Test
    void tamperedCiphertextGivesAnotherSecretAndNoError() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String encapsulated = encapsulate(demoKey, id);
        byte[] ciphertext = decode(JsonPath.read(encapsulated, "$.ciphertext"));
        ciphertext[100] ^= 1;

        // FIPS 203 implicit rejection: same 200 answer, different secret. The API must not reveal the tampering.
        byte[] secret = decapsulate(demoKey, id, Base64.getEncoder().encodeToString(ciphertext));
        assertEquals(32, secret.length);
        assertFalse(java.util.Arrays.equals(decode(JsonPath.read(encapsulated, "$.sharedSecret")), secret));
    }

    @Test
    void badCiphertextsGetOneFixedAnswer() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String good = JsonPath.read(encapsulate(demoKey, id), "$.ciphertext");
        byte[] raw = decode(good);
        Base64.Encoder b64 = Base64.getEncoder();
        String[] bodies = {
            "",
            "{}",
            "{\"ciphertext\":\"\"}",
            "{\"ciphertext\":null}",
            "{\"ciphertext\":123}",
            json(good.substring(4)), // too short
            json(good + "AAAA"), // too long
            json(b64.encodeToString(java.util.Arrays.copyOf(raw, 1567))),
            json(b64.encodeToString(java.util.Arrays.copyOf(raw, 1569))),
            json(good.replace('+', '-').replace('/', '_')), // URL-safe alphabet
            json(good.substring(0, 100) + "\\n" + good.substring(100)), // line break
            json(good.substring(0, 2091) + "*"), // not Base64
            json(b64.encodeToString(ciphertext768())), // ML-KEM-768 ciphertext
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"invalid_request\"}", true));
        }
    }

    @Test
    void signatureKeysCannotBeUsedForKeyExchange() throws Exception {
        String id = generate(demoKey, "ML-DSA-65");

        mockMvc.perform(post("/api/v1/keys/" + id + "/encapsulate").header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("wrong_key_type"));
        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json("AAAA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("wrong_key_type"));
    }

    @Test
    void onlyActiveKeysCanBeUsed() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String ciphertext = JsonPath.read(encapsulate(demoKey, id), "$.ciphertext");
        jdbc.update("UPDATE key_pairs SET status = 'revoked' WHERE key_pair_id = ?", UUID.fromString(id));

        mockMvc.perform(post("/api/v1/keys/" + id + "/encapsulate").header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key_not_active"));
        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(ciphertext)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key_not_active"));
    }

    @Test
    void anotherTeamsKeyLooksLikeAnUnknownKey() throws Exception {
        String otherKey = apiKeys.issue(OTHER_ACCOUNT, "test").key();
        String theirs = generate(otherKey, "ML-KEM-1024");
        String ciphertext = JsonPath.read(encapsulate(otherKey, theirs), "$.ciphertext");

        for (String id : new String[] {theirs, UUID.randomUUID().toString(), "not-a-uuid"}) {
            mockMvc.perform(post("/api/v1/keys/" + id + "/encapsulate").header(ApiKeys.HEADER, demoKey))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("key_not_found"));
            mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(json(ciphertext)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("key_not_found"));
        }
    }

    @Test
    void withoutAnApiKeyNothingHappens() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");

        mockMvc.perform(post("/api/v1/keys/" + id + "/encapsulate")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate")
                        .contentType(MediaType.APPLICATION_JSON).content(json("AAAA")))
                .andExpect(status().isUnauthorized());
        assertEquals(List.of("generate"), auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                .map(AuditLogEntity::getAction).toList());
    }

    @Test
    void everyCallIsAuditedWithTheApiKeyAndNoSecrets() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String ciphertext = JsonPath.read(encapsulate(demoKey, id), "$.ciphertext");
        decapsulate(demoKey, id, ciphertext);
        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                .contentType(MediaType.APPLICATION_JSON).content(json("AAAA")));

        List<String> entries = auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                .map(e -> e.getAction() + ":" + e.getStatus() + ":" + demoKeyId.equals(e.getApiKeyId()))
                .sorted().toList();
        assertEquals(List.of("decapsulate:failure:true", "decapsulate:success:true", "encapsulate:success:true",
                "generate:success:true"), entries);
    }

    @Test
    void answersHoldOnlyTheDocumentedFields() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String encapsulated = encapsulate(demoKey, id);
        Map<String, Object> fields = JsonPath.read(encapsulated, "$");
        assertEquals(List.of("ciphertext", "keyPairId", "sharedSecret"), fields.keySet().stream().sorted().toList());

        String decapsulated = mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate")
                        .header(ApiKeys.HEADER, demoKey).contentType(MediaType.APPLICATION_JSON)
                        .content(json(JsonPath.read(encapsulated, "$.ciphertext"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> decFields = JsonPath.read(decapsulated, "$");
        assertEquals(List.of("keyPairId", "sharedSecret"), decFields.keySet().stream().sorted().toList());
        assertFalse(encapsulated.toLowerCase().contains("private"));
    }

    @Test
    void ciphertextDecodingIsStrict() {
        byte[] raw = new byte[1568];
        String good = Base64.getEncoder().encodeToString(raw);
        assertEquals(1568, KeyExchangeService.decodeCiphertext(good).length);
        assertEquals(null, KeyExchangeService.decodeCiphertext(null));
        assertEquals('=', good.charAt(2091));
        // Same length and alphabet, but unused bits set in the last group: a second spelling of the same bytes.
        assertEquals(null, KeyExchangeService.decodeCiphertext(good.substring(0, 2090) + "B="));
        assertEquals(null, KeyExchangeService.decodeCiphertext(good.substring(0, 2091) + "A"));
        assertEquals(null, KeyExchangeService.decodeCiphertext("A".repeat(2092)));
    }

    @Test
    void aDamagedStoredPrivateKeyGivesAFixedErrorAndIsAudited() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String ciphertext = JsonPath.read(encapsulate(demoKey, id), "$.ciphertext");
        byte[] stored = jdbc.queryForObject("SELECT private_key_ciphertext FROM key_pairs WHERE key_pair_id = ?",
                byte[].class, UUID.fromString(id));
        stored[stored.length - 1] ^= 1;
        jdbc.update("UPDATE key_pairs SET private_key_ciphertext = ? WHERE key_pair_id = ?", stored,
                UUID.fromString(id));

        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(ciphertext)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"operation_failed\"}", true));
        assertEquals(List.of("decapsulate:failure", "encapsulate:success", "generate:success"),
                auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                        .map(e -> e.getAction() + ":" + e.getStatus()).sorted().toList());
    }

    @Test
    void aCallForAnUnknownKeyIsAuditedWithoutAKeyId() throws Exception {
        String count = "SELECT COUNT(*) FROM audit_logs WHERE account_id = ? AND api_key_id = ?"
                + " AND key_pair_id IS NULL AND action = 'encapsulate' AND status = 'failure'";

        mockMvc.perform(post("/api/v1/keys/" + UUID.randomUUID() + "/encapsulate").header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isNotFound());

        assertEquals(1, jdbc.queryForObject(count, Integer.class, DEMO_ACCOUNT, demoKeyId));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void secretsAndKeysNeverReachTheLogs(CapturedOutput output) throws Exception {
        String id = generate(demoKey, "ML-KEM-1024");
        String encapsulated = encapsulate(demoKey, id);
        String ciphertext = JsonPath.read(encapsulated, "$.ciphertext");
        String secret = JsonPath.read(encapsulated, "$.sharedSecret");
        decapsulate(demoKey, id, ciphertext);
        // A failing call too, so the error log line is checked as well.
        jdbc.update("UPDATE key_pairs SET private_key_ciphertext = ? WHERE key_pair_id = ?", new byte[40],
                UUID.fromString(id));
        mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(ciphertext)))
                .andExpect(status().isInternalServerError());

        String logged = output.getAll();
        assertTrue(logged.contains("decapsulate failed for key pair " + id), "the failure is logged");
        for (String forbidden : new String[] {secret, ciphertext.substring(0, 40), demoKey, demoKey.substring(8)}) {
            assertFalse(logged.contains(forbidden), "a secret, ciphertext or API key reached the log");
        }
    }

    // ---- helpers ----

    private String generate(String apiKey, String algorithm) throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"" + algorithm + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.keyPairId");
    }

    private String encapsulate(String apiKey, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/keys/" + id + "/encapsulate").header(ApiKeys.HEADER, apiKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private byte[] decapsulate(String apiKey, String id, String ciphertext) throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/" + id + "/decapsulate").header(ApiKeys.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(ciphertext)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return decode(JsonPath.read(body, "$.sharedSecret"));
    }

    private static String json(String ciphertext) {
        return "{\"ciphertext\":\"" + ciphertext + "\"}";
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static byte[] ciphertext768() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.initialize(MLKEMParameterSpec.ml_kem_768);
        KeyGenerator sender = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        sender.init(new KEMGenerateSpec(generator.generateKeyPair().getPublic(), "AES"));
        return ((SecretKeyWithEncapsulation) sender.generateKey()).getEncapsulation();
    }
}
