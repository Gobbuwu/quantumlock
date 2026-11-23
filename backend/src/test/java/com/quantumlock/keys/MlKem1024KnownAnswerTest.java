package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.MLKEMParameters;
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Known-answer tests for ML-KEM-1024 with NIST's own FIPS 203 test vectors (ACVP-Server, see
 * src/test/resources/nist/ml-kem-1024-acvp.json for the exact source commit). Each test runs the
 * application's own code path: the key generator the API uses, and the encapsulate and decapsulate
 * steps of KeyExchangeService. Matching NIST's expected bytes shows QuantumLock produces the same
 * keys, ciphertexts and shared secrets as any other FIPS 203 implementation.
 */
class MlKem1024KnownAnswerTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final JsonNode VECTORS = load();

    @ParameterizedTest(name = "keyGen tcId {0}")
    @MethodSource("keyGen")
    void keyGenerationMatchesNist(int tcId, JsonNode test) throws Exception {
        // FIPS 203 ML-KEM.KeyGen draws the 32-byte seed d, then the 32-byte seed z.
        SeedRandom seeds = new SeedRandom(bytes(test, "d"), bytes(test, "z"));
        KeyPair keyPair = new PostQuantumKeyGenerator(seeds).generate(Algorithm.ML_KEM_1024);

        byte[] ek = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()).getPublicKeyData().getBytes();
        MLKEMPrivateKeyParameters dk = (MLKEMPrivateKeyParameters) PrivateKeyFactory.createKey(keyPair.getPrivate().getEncoded());
        assertArrayEquals(bytes(test, "ek"), ek, "encapsulation key");
        assertArrayEquals(bytes(test, "dk"), dk.getEncoded(), "decapsulation key");
        seeds.assertAllUsed();
    }

    @ParameterizedTest(name = "encapsulation tcId {0}")
    @MethodSource("encapsulation")
    void encapsulationMatchesNist(int tcId, JsonNode test) throws Exception {
        byte[] x509PublicKey = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
                new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_1024, bytes(test, "ek"))).getEncoded();
        // FIPS 203 ML-KEM.Encaps draws the 32-byte message m.
        SeedRandom m = new SeedRandom(bytes(test, "m"));

        KeyExchangeService.Encapsulation result = KeyExchangeService.encapsulate(x509PublicKey, m);

        assertArrayEquals(bytes(test, "c"), result.ciphertext(), "ciphertext");
        assertArrayEquals(bytes(test, "k"), result.sharedSecret(), "shared secret");
        m.assertAllUsed();
    }

    /** Covers valid ciphertexts and modified ones, where FIPS 203 implicit rejection gives a fixed pseudo-random K. */
    @ParameterizedTest(name = "decapsulation tcId {0}: {2}")
    @MethodSource("decapsulation")
    void decapsulationMatchesNist(int tcId, JsonNode test, String reason) throws Exception {
        byte[] pkcs8PrivateKey = PrivateKeyInfoFactory.createPrivateKeyInfo(
                new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_1024, bytes(test, "dk"))).getEncoded();

        byte[] sharedSecret = KeyExchangeService.decapsulate(pkcs8PrivateKey, bytes(test, "c"));

        assertArrayEquals(bytes(test, "k"), sharedSecret, "shared secret");
    }

    @Test
    void vectorFileHasEveryMlKem1024Case() {
        assertEquals(25, VECTORS.get("keyGen").get("tests").size());
        assertEquals(25, VECTORS.get("encapsulation").get("tests").size());
        assertEquals(10, VECTORS.get("decapsulation").get("tests").size());
    }

    static Stream<Arguments> keyGen() {
        return cases("keyGen").stream().map(t -> Arguments.of(t.get("tcId").asInt(), t));
    }

    static Stream<Arguments> encapsulation() {
        return cases("encapsulation").stream().map(t -> Arguments.of(t.get("tcId").asInt(), t));
    }

    static Stream<Arguments> decapsulation() {
        return cases("decapsulation").stream()
                .map(t -> Arguments.of(t.get("tcId").asInt(), t, t.get("reason").asString()));
    }

    private static List<JsonNode> cases(String group) {
        List<JsonNode> tests = new ArrayList<>();
        VECTORS.get(group).get("tests").forEach(tests::add);
        return tests;
    }

    private static byte[] bytes(JsonNode test, String field) {
        return HEX.parseHex(test.get(field).asString());
    }

    private static JsonNode load() {
        try (InputStream in = MlKem1024KnownAnswerTest.class.getResourceAsStream("/nist/ml-kem-1024-acvp.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing /nist/ml-kem-1024-acvp.json");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
