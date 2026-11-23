package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.KeyGenerator;

import com.jayway.jsonpath.JsonPath;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.IssuedApiKey;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.crypto.CryptoProviders;
import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the key generation endpoint (task 3.2) through HTTP, then looks in the database to prove the
 * private key is stored only encrypted and still works after decryption.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KeyApiTest {

    /** The demo team and a second team from database/seed.sql. */
    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyPairRepository keyPairs;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private PrivateKeyEncryptor encryptor;

    @Autowired
    private ApiKeyService apiKeys;

    /** A fresh API key of the demo team for each test. */
    private String demoKey;
    private UUID demoKeyId;

    @BeforeEach
    void issueApiKey() {
        IssuedApiKey issued = apiKeys.issue(DEMO_ACCOUNT, "test");
        demoKey = issued.key();
        demoKeyId = issued.entity().getApiKeyId();
    }

    @Test
    void mlKemKeyIsStoredEncryptedAndStillWorks() throws Exception {
        String body = generate("ML-KEM-1024");
        UUID id = UUID.fromString(JsonPath.read(body, "$.keyPairId"));
        byte[] publicKeyBytes = Base64.getDecoder().decode((String) JsonPath.read(body, "$.publicKey"));

        KeyPairEntity row = keyPairs.findById(id).orElseThrow();
        assertEquals(DEMO_ACCOUNT, row.getAccountId());
        assertEquals("ML-KEM-1024", row.getAlgorithm());
        assertArrayEquals(publicKeyBytes, row.getPublicKey());

        byte[] privateKeyBytes = encryptor.decrypt(row.getPrivateKeyCiphertext(), id, DEMO_ACCOUNT);
        assertFalse(contains(row.getPrivateKeyCiphertext(), Arrays.copyOf(privateKeyBytes, 32)),
                "the database must not hold the private key in plain form");

        // The returned public key and the stored private key are one working ML-KEM-1024 pair.
        PublicKey publicKey = KeyFactory.getInstance("ML-KEM-1024", CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        PrivateKey privateKey = KeyFactory.getInstance("ML-KEM-1024", CryptoProviders.BC)
                .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        KeyGenerator sender = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        sender.init(new KEMGenerateSpec(publicKey, "AES"));
        SecretKeyWithEncapsulation sent = (SecretKeyWithEncapsulation) sender.generateKey();
        KeyGenerator receiver = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        receiver.init(new KEMExtractSpec(privateKey, sent.getEncapsulation(), "AES"));
        assertArrayEquals(sent.getEncoded(), receiver.generateKey().getEncoded());
    }

    @Test
    void mlDsaKeyIsStoredEncryptedAndStillWorks() throws Exception {
        String body = generate("ML-DSA-65");
        UUID id = UUID.fromString(JsonPath.read(body, "$.keyPairId"));
        byte[] publicKeyBytes = Base64.getDecoder().decode((String) JsonPath.read(body, "$.publicKey"));

        KeyPairEntity row = keyPairs.findById(id).orElseThrow();
        byte[] privateKeyBytes = encryptor.decrypt(row.getPrivateKeyCiphertext(), id, DEMO_ACCOUNT);

        byte[] message = "QuantumLock".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        signer.initSign(KeyFactory.getInstance("ML-DSA", CryptoProviders.BC)
                .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes)));
        signer.update(message);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        verifier.initVerify(KeyFactory.getInstance("ML-DSA", CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes)));
        verifier.update(message);
        assertTrue(verifier.verify(signature));
        assertEquals(3309, signature.length);
    }

    @Test
    void responseNeverContainsThePrivateKey() throws Exception {
        String body = generate("ML-KEM-1024");

        assertFalse(body.toLowerCase().contains("private"), body);
        Map<String, Object> fields = JsonPath.read(body, "$");
        assertEquals(List.of("algorithm", "createdAt", "gracePeriodEndsAt", "keyPairId", "publicKey", "replacedBy",
                "rotatedAt", "status"), fields.keySet().stream().sorted().toList());
        // Only rotation fills these in.
        assertEquals(null, fields.get("rotatedAt"));
        assertEquals(null, fields.get("gracePeriodEndsAt"));
        assertEquals(null, fields.get("replacedBy"));
    }

    @Test
    void generationIsRecordedInTheAuditLog() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(generate("ML-DSA-65"), "$.keyPairId"));

        List<AuditLogEntity> entries = auditLogs.findByKeyPairId(id);
        assertEquals(1, entries.size());
        assertEquals("generate", entries.get(0).getAction());
        assertEquals("success", entries.get(0).getStatus());
        assertEquals(DEMO_ACCOUNT, entries.get(0).getAccountId());
        assertEquals(demoKeyId, entries.get(0).getApiKeyId(), "the audit entry names the API key that was used");
    }

    @Test
    void unsupportedAlgorithmsAreRefusedAndNothingIsStored() throws Exception {
        long before = keyPairs.count();

        for (String algorithm : new String[] {"RSA-2048", "ML-KEM-768", "ML-KEM-512", "ml-kem-1024", " ML-KEM-1024"}) {
            mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"algorithm\":\"" + algorithm + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("unsupported_algorithm"));
        }
        assertEquals(before, keyPairs.count());
    }

    @Test
    void malformedRequestsAreRefused() throws Exception {
        for (String content : new String[] {"", "{}", "{\"algorithm\":\"\"}", "{\"algorithm\":null}", "not json",
                "{\"algorithm\":[\"ML-KEM-1024\"]}"}) {
            mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
    }

    @Test
    void listShowsOnlyThisAccountsKeysNewestFirst() throws Exception {
        UUID mine = UUID.fromString(JsonPath.read(generate("ML-KEM-1024"), "$.keyPairId"));
        UUID theirs = storeKeyForOtherAccount();

        String body = mockMvc.perform(get("/api/v1/keys").header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$[*].keyPairId");
        List<String> createdAt = JsonPath.read(body, "$[*].createdAt");
        assertTrue(ids.contains(mine.toString()));
        assertFalse(ids.contains(theirs.toString()), "another team's key must never be listed");
        assertFalse(body.toLowerCase().contains("private"));
        for (int i = 1; i < createdAt.size(); i++) {
            assertFalse(Instant.parse(createdAt.get(i)).isAfter(Instant.parse(createdAt.get(i - 1))));
        }
    }

    @Test
    void oneKeyCanBeReadButNotAnotherAccountsKey() throws Exception {
        UUID mine = UUID.fromString(JsonPath.read(generate("ML-DSA-65"), "$.keyPairId"));
        UUID theirs = storeKeyForOtherAccount();

        mockMvc.perform(get("/api/v1/keys/" + mine).header(ApiKeys.HEADER, demoKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyPairId").value(mine.toString()))
                .andExpect(jsonPath("$.algorithm").value("ML-DSA-65"));

        // Another team's key, an unknown id and something that is not an id all look the same.
        for (String id : new String[] {theirs.toString(), UUID.randomUUID().toString(), "not-a-uuid"}) {
            mockMvc.perform(get("/api/v1/keys/" + id).header(ApiKeys.HEADER, demoKey))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("key_not_found"));
        }
    }

    private String generate(String algorithm) throws Exception {
        return mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"algorithm\":\"" + algorithm + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.algorithm").value(algorithm))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
    }

    /** Stores a key pair for the second demo team directly, as if that team had generated it. */
    private UUID storeKeyForOtherAccount() {
        UUID id = UUID.randomUUID();
        byte[] ciphertext = encryptor.encrypt(new byte[] {1, 2, 3}, id, OTHER_ACCOUNT);
        keyPairs.save(new KeyPairEntity(id, OTHER_ACCOUNT, Algorithm.ML_KEM_1024, new byte[] {4, 5, 6}, ciphertext,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return id;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
