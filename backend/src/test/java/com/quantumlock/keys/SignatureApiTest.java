package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.auth.ApiKeyService;
import com.quantumlock.auth.ApiKeys;
import com.quantumlock.auth.IssuedApiKey;
import com.quantumlock.crypto.CryptoProviders;
import org.bouncycastle.jcajce.spec.ContextParameterSpec;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
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
 * ML-DSA-65 sign and verify (KAN-29) through HTTP: round trips with the API and with an independent Bouncy Castle
 * verifier, tampering, context strings, strict input checks, team isolation and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureApiTest {

    private static final UUID DEMO_ACCOUNT = UUID.fromString("5f1c3a9e-7b2d-4e8f-9a6c-1d0e2b3c4a51");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a83d6e21-4c5f-4b07-8e19-6f2a7c9d0b34");
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final String MESSAGE = b64("Invoice 2026-118: pay 1,250.00 to account 42");

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
    void signThenVerify() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();

        String signed = sign(demoKey, id, MESSAGE, null);
        assertEquals(id, JsonPath.read(signed, "$.keyPairId"));
        String signature = JsonPath.read(signed, "$.signature");
        assertEquals(3309, decode(signature).length);

        assertTrue(verify(demoKey, id, MESSAGE, signature, null));
    }

    @Test
    void anIndependentVerifierAcceptsTheSignatureWithThePublicKeyAlone() throws Exception {
        Generated key = generate(demoKey, "ML-DSA-65");
        byte[] context = "invoices".getBytes(StandardCharsets.UTF_8);
        String signature = JsonPath.read(sign(demoKey, key.id(), MESSAGE, B64.encodeToString(context)), "$.signature");

        // What a recipient does with only the published public key and a plain FIPS 204 ML-DSA.Verify.
        PublicKey publicKey = KeyFactory.getInstance("ML-DSA-65", CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(decode(key.publicKey())));
        Signature verifier = Signature.getInstance("ML-DSA-65", CryptoProviders.BC);
        verifier.initVerify(publicKey);
        verifier.setParameter(new ContextParameterSpec(context));
        verifier.update(decode(MESSAGE));
        assertTrue(verifier.verify(decode(signature)));
    }

    @Test
    void anIndependentSignatureIsAcceptedForTheMatchingKeyOnly() throws Exception {
        // A signature made outside QuantumLock with another ML-DSA-65 key must not verify under ours.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-DSA", CryptoProviders.BC);
        generator.initialize(MLDSAParameterSpec.ml_dsa_65);
        KeyPair outside = generator.generateKeyPair();
        Signature signer = Signature.getInstance("ML-DSA-65", CryptoProviders.BC);
        signer.initSign(outside.getPrivate());
        signer.update(decode(MESSAGE));
        String foreign = B64.encodeToString(signer.sign());

        String id = generate(demoKey, "ML-DSA-65").id();
        assertFalse(verify(demoKey, id, MESSAGE, foreign, null));
    }

    @Test
    void signingIsRandomizedAndEverySignatureVerifies() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String first = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");
        String second = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");

        assertNotEquals(first, second);
        assertTrue(verify(demoKey, id, MESSAGE, first, null));
        assertTrue(verify(demoKey, id, MESSAGE, second, null));
    }

    @Test
    void anyChangeMakesTheSignatureInvalidWithoutAnError() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String otherId = generate(demoKey, "ML-DSA-65").id();
        String context = b64("invoices");
        String signature = JsonPath.read(sign(demoKey, id, MESSAGE, context), "$.signature");
        byte[] flipped = decode(signature);
        flipped[1000] ^= 1;

        assertTrue(verify(demoKey, id, MESSAGE, signature, context));
        assertFalse(verify(demoKey, id, b64("Invoice 2026-118: pay 9,250.00 to account 42"), signature, context));
        assertFalse(verify(demoKey, id, MESSAGE, B64.encodeToString(flipped), context));
        assertFalse(verify(demoKey, id, MESSAGE, signature, b64("payroll")));
        assertFalse(verify(demoKey, id, MESSAGE, signature, null)); // no context is not the same context
        assertFalse(verify(demoKey, otherId, MESSAGE, signature, context));
        assertFalse(verify(demoKey, id, MESSAGE, B64.encodeToString(new byte[3309]), context));
    }

    @Test
    void noContextAndAnEmptyContextAreTheSame() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String withoutContext = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");
        String withEmptyContext = JsonPath.read(sign(demoKey, id, MESSAGE, ""), "$.signature");

        assertTrue(verify(demoKey, id, MESSAGE, withoutContext, ""));
        assertTrue(verify(demoKey, id, MESSAGE, withEmptyContext, null));
    }

    @Test
    void theLimitsAreInclusive() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String largest = B64.encodeToString(new byte[SignatureService.MAX_MESSAGE_BYTES]);
        String longestContext = B64.encodeToString(new byte[SignatureService.MAX_CONTEXT_BYTES]);
        String oneByte = B64.encodeToString(new byte[1]);

        String signature = JsonPath.read(sign(demoKey, id, largest, longestContext), "$.signature");
        assertTrue(verify(demoKey, id, largest, signature, longestContext));
        signature = JsonPath.read(sign(demoKey, id, oneByte, null), "$.signature");
        assertTrue(verify(demoKey, id, oneByte, signature, null));
    }

    @Test
    void badSignRequestsGetOneFixedAnswer() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String[] bodies = {
            "",
            "{}",
            "{\"message\":\"\"}",
            "{\"message\":null}",
            "{\"message\":123}",
            signJson("AAA", null), // not a whole Base64 group
            signJson("AAF=", null), // unused bits set: a second spelling of the same bytes
            signJson(MESSAGE.replace('+', '-').replace('/', '_') + "-_", null), // URL-safe alphabet
            signJson(MESSAGE.substring(0, 8) + "\\n" + MESSAGE.substring(8), null), // line break
            signJson("****", null), // not Base64
            signJson(B64.encodeToString(new byte[SignatureService.MAX_MESSAGE_BYTES + 1]), null),
            signJson(MESSAGE, B64.encodeToString(new byte[SignatureService.MAX_CONTEXT_BYTES + 1])),
            signJson(MESSAGE, "A"),
            "{\"message\":\"" + MESSAGE + "\",\"context\":5}",
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"invalid_request\"}", true));
        }
    }

    @Test
    void badVerifyRequestsGetOneFixedAnswer() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String signature = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");
        byte[] raw = decode(signature);
        String[] bodies = {
            "{}",
            "{\"message\":\"" + MESSAGE + "\"}",
            "{\"signature\":\"" + signature + "\"}",
            verifyJson(MESSAGE, "", null),
            verifyJson("", signature, null),
            verifyJson(MESSAGE, B64.encodeToString(Arrays.copyOf(raw, 3308)), null),
            verifyJson(MESSAGE, B64.encodeToString(Arrays.copyOf(raw, 3310)), null),
            verifyJson(MESSAGE, signature.substring(4), null),
            verifyJson(MESSAGE, signature + "AAAA", null),
            verifyJson(MESSAGE, signature.replace('+', '-').replace('/', '_'), null),
            verifyJson(MESSAGE, signature.substring(0, 100) + "\\n" + signature.substring(100), null),
            verifyJson(MESSAGE, signature, B64.encodeToString(new byte[SignatureService.MAX_CONTEXT_BYTES + 1])),
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"invalid_request\"}", true));
        }
    }

    @Test
    void keyExchangeKeysCannotSign() throws Exception {
        String id = generate(demoKey, "ML-KEM-1024").id();

        mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("wrong_key_type"));
        mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(MESSAGE, B64.encodeToString(new byte[3309]), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("wrong_key_type"));
    }

    @Test
    void onlyActiveKeysCanBeUsed() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String signature = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");
        jdbc.update("UPDATE key_pairs SET status = 'revoked' WHERE key_pair_id = ?", UUID.fromString(id));

        mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key_not_active"));
        mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(verifyJson(MESSAGE, signature, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key_not_active"));
    }

    @Test
    void anotherTeamsKeyLooksLikeAnUnknownKey() throws Exception {
        String otherKey = apiKeys.issue(OTHER_ACCOUNT, "test").key();
        String theirs = generate(otherKey, "ML-DSA-65").id();
        String signature = JsonPath.read(sign(otherKey, theirs, MESSAGE, null), "$.signature");

        for (String id : new String[] {theirs, UUID.randomUUID().toString(), "not-a-uuid"}) {
            mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("key_not_found"));
            mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, demoKey)
                            .contentType(MediaType.APPLICATION_JSON).content(verifyJson(MESSAGE, signature, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("key_not_found"));
        }
    }

    @Test
    void withoutAnApiKeyNothingHappens() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();

        mockMvc.perform(post("/api/v1/keys/" + id + "/sign")
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/keys/" + id + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(MESSAGE, B64.encodeToString(new byte[3309]), null)))
                .andExpect(status().isUnauthorized());
        assertEquals(List.of("generate"), auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                .map(AuditLogEntity::getAction).toList());
    }

    @Test
    void everyCallIsAuditedWithTheApiKeyAndAFalseAnswerIsNotAFailure() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String signature = JsonPath.read(sign(demoKey, id, MESSAGE, null), "$.signature");
        verify(demoKey, id, MESSAGE, signature, null);
        verify(demoKey, id, b64("something else"), signature, null);
        mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                .contentType(MediaType.APPLICATION_JSON).content(signJson("****", null)));

        List<String> entries = auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                .map(e -> e.getAction() + ":" + e.getStatus() + ":" + demoKeyId.equals(e.getApiKeyId()))
                .sorted().toList();
        assertEquals(List.of("generate:success:true", "sign:failure:true", "sign:success:true",
                "verify:success:true", "verify:success:true"), entries);
    }

    @Test
    void answersHoldOnlyTheDocumentedFields() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String signed = sign(demoKey, id, MESSAGE, null);
        Map<String, Object> fields = JsonPath.read(signed, "$");
        assertEquals(List.of("keyPairId", "signature"), fields.keySet().stream().sorted().toList());

        String verified = mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyJson(MESSAGE, JsonPath.read(signed, "$.signature"), null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> verifyFields = JsonPath.read(verified, "$");
        assertEquals(List.of("keyPairId", "valid"), verifyFields.keySet().stream().sorted().toList());
        assertFalse(signed.toLowerCase().contains("private"));
    }

    @Test
    void aDamagedStoredPrivateKeyGivesAFixedErrorAndIsAudited() throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        byte[] stored = jdbc.queryForObject("SELECT private_key_ciphertext FROM key_pairs WHERE key_pair_id = ?",
                byte[].class, UUID.fromString(id));
        stored[stored.length - 1] ^= 1;
        jdbc.update("UPDATE key_pairs SET private_key_ciphertext = ? WHERE key_pair_id = ?", stored,
                UUID.fromString(id));

        mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"operation_failed\"}", true));
        assertEquals(List.of("generate:success", "sign:failure"),
                auditLogs.findByKeyPairId(UUID.fromString(id)).stream()
                        .map(e -> e.getAction() + ":" + e.getStatus()).sorted().toList());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void messagesSignaturesAndKeysNeverReachTheLogs(CapturedOutput output) throws Exception {
        String id = generate(demoKey, "ML-DSA-65").id();
        String signature = JsonPath.read(sign(demoKey, id, MESSAGE, b64("invoices")), "$.signature");
        verify(demoKey, id, MESSAGE, signature, b64("invoices"));
        // A failing call too, so the error log line is checked as well.
        jdbc.update("UPDATE key_pairs SET private_key_ciphertext = ? WHERE key_pair_id = ?", new byte[40],
                UUID.fromString(id));
        mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, demoKey)
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(MESSAGE, null)))
                .andExpect(status().isInternalServerError());

        String logged = output.getAll();
        assertTrue(logged.contains("sign failed for key pair " + id), "the failure is logged");
        for (String forbidden : new String[] {MESSAGE, signature.substring(0, 40), b64("invoices"), demoKey,
                demoKey.substring(8)}) {
            assertFalse(logged.contains(forbidden), "a message, signature, context or API key reached the log");
        }
    }

    @Test
    void signatureDecodingIsStrict() {
        String good = B64.encodeToString(new byte[3309]);
        assertEquals(3309, StrictBase64.decode(good, 3309, 3309).length);
        assertEquals(null, StrictBase64.decode(null, 3309, 3309));
        assertEquals(null, StrictBase64.decode(good.substring(0, good.length() - 4) + "AAA=", 3309, 3309));
        // Unused bits set in the last group: a second spelling of the same byte.
        assertEquals(1, SignatureService.decodeMessage("AA==").length);
        assertEquals(null, SignatureService.decodeMessage("AB=="));
        assertEquals(0, SignatureService.decodeContext(null).length);
        assertEquals(0, SignatureService.decodeContext("").length);
        assertEquals(null, SignatureService.decodeMessage(""));
    }

    // ---- helpers ----

    private record Generated(String id, String publicKey) {
    }

    private Generated generate(String apiKey, String algorithm) throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/generate").header(ApiKeys.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"algorithm\":\"" + algorithm + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Generated(JsonPath.read(body, "$.keyPairId"), JsonPath.read(body, "$.publicKey"));
    }

    private String sign(String apiKey, String id, String message, String context) throws Exception {
        return mockMvc.perform(post("/api/v1/keys/" + id + "/sign").header(ApiKeys.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(signJson(message, context)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private boolean verify(String apiKey, String id, String message, String signature, String context)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/keys/" + id + "/verify").header(ApiKeys.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(verifyJson(message, signature, context)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyPairId").value(id))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.valid");
    }

    private static String signJson(String message, String context) {
        return "{\"message\":\"" + message + "\"" + (context == null ? "" : ",\"context\":\"" + context + "\"") + "}";
    }

    private static String verifyJson(String message, String signature, String context) {
        return "{\"message\":\"" + message + "\",\"signature\":\"" + signature + "\""
                + (context == null ? "" : ",\"context\":\"" + context + "\"") + "}";
    }

    private static String b64(String text) {
        return B64.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
