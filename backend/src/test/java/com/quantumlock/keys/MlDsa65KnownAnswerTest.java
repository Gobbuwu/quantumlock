package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.MLDSAParameters;
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters;
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
 * Known-answer tests for ML-DSA-65 with NIST's own FIPS 204 test vectors (ACVP-Server, see
 * src/test/resources/nist/ml-dsa-65-acvp.json for the exact source commit). Each test runs the application's own
 * code path: the key generator the API uses, and the sign and verify steps of SignatureService. Matching NIST's
 * expected bytes shows QuantumLock produces the same keys and signatures as any other FIPS 204 implementation, and
 * accepts and refuses the same signatures.
 */
class MlDsa65KnownAnswerTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final JsonNode VECTORS = load();

    @ParameterizedTest(name = "keyGen tcId {0}")
    @MethodSource("keyGen")
    void keyGenerationMatchesNist(int tcId, JsonNode test) throws Exception {
        // FIPS 204 ML-DSA.KeyGen draws the 32-byte seed xi.
        SeedRandom seed = new SeedRandom(bytes(test, "seed"));
        KeyPair keyPair = new PostQuantumKeyGenerator(seed).generate(Algorithm.ML_DSA_65);

        byte[] pk = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()).getPublicKeyData().getBytes();
        MLDSAPrivateKeyParameters sk = (MLDSAPrivateKeyParameters) PrivateKeyFactory.createKey(keyPair.getPrivate().getEncoded());
        assertArrayEquals(bytes(test, "pk"), pk, "public key");
        assertArrayEquals(bytes(test, "sk"), sk.getEncoded(), "private key");
        seed.assertAllUsed();
    }

    /** Deterministic cases use rnd = 32 zero bytes (FIPS 204), hedged ones the rnd NIST gives. */
    @ParameterizedTest(name = "sigGen tcId {0}, deterministic {2}")
    @MethodSource("sigGen")
    void signingMatchesNist(int tcId, JsonNode test, boolean deterministic) throws Exception {
        byte[] pkcs8PrivateKey = PrivateKeyInfoFactory.createPrivateKeyInfo(
                new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, bytes(test, "sk"))).getEncoded();
        SeedRandom rnd = new SeedRandom(deterministic ? new byte[32] : bytes(test, "rnd"));

        byte[] signature = SignatureService.sign(pkcs8PrivateKey, bytes(test, "message"), bytes(test, "context"), rnd);

        assertArrayEquals(bytes(test, "signature"), signature, "signature");
        rnd.assertAllUsed();
    }

    /** Valid signatures, and ones with a changed message or a changed part of the signature. */
    @ParameterizedTest(name = "sigVer tcId {0}: {2}")
    @MethodSource("sigVer")
    void verificationMatchesNist(int tcId, JsonNode test, String reason) throws Exception {
        byte[] x509PublicKey = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
                new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, bytes(test, "pk"))).getEncoded();

        boolean valid = SignatureService.verify(x509PublicKey, bytes(test, "message"), bytes(test, "context"),
                bytes(test, "signature"));

        assertEquals(test.get("testPassed").asBoolean(), valid, reason);
    }

    @Test
    void vectorFileHasEveryMlDsa65Case() {
        assertEquals(25, VECTORS.get("keyGen").get("tests").size());
        assertEquals(30, VECTORS.get("sigGen").get("tests").size());
        assertEquals(15, VECTORS.get("sigVer").get("tests").size());
        assertEquals(15, cases("sigGen").stream().filter(t -> t.get("deterministic").asBoolean()).count());
    }

    static Stream<Arguments> keyGen() {
        return cases("keyGen").stream().map(t -> Arguments.of(t.get("tcId").asInt(), t));
    }

    static Stream<Arguments> sigGen() {
        return cases("sigGen").stream()
                .map(t -> Arguments.of(t.get("tcId").asInt(), t, t.get("deterministic").asBoolean()));
    }

    static Stream<Arguments> sigVer() {
        return cases("sigVer").stream()
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
        try (InputStream in = MlDsa65KnownAnswerTest.class.getResourceAsStream("/nist/ml-dsa-65-acvp.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing /nist/ml-dsa-65-acvp.json");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
