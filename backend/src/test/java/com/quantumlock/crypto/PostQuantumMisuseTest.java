package com.quantumlock.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.KeyGenerator;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.interfaces.MLKEMPublicKey;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Feeds Bouncy Castle the bad input an attacker would send (tampered ciphertexts, malformed or
 * swapped keys, forged signatures) and pins down how it reacts. The key endpoints in tasks 3.2
 * and 3.4 rely on exactly this behaviour, so a library upgrade that changed it would fail here.
 * Plain JUnit, no Spring context.
 */
class PostQuantumMisuseTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static KeyPair kem1024;
    private static KeyPair otherKem1024;
    private static KeyPair kem768;
    private static KeyPair dsa65;
    private static KeyPair otherDsa65;

    @BeforeAll
    static void generateKeys() throws Exception {
        CryptoProviders.register();
        kem1024 = kemKeyPair(MLKEMParameterSpec.ml_kem_1024);
        otherKem1024 = kemKeyPair(MLKEMParameterSpec.ml_kem_1024);
        kem768 = kemKeyPair(MLKEMParameterSpec.ml_kem_768);
        dsa65 = dsaKeyPair();
        otherDsa65 = dsaKeyPair();
    }

    // ---- ML-KEM: ciphertexts ----

    @Test
    void everyEncapsulationUsesFreshRandomness() throws Exception {
        SecretKeyWithEncapsulation first = encapsulate(kem1024.getPublic());
        SecretKeyWithEncapsulation second = encapsulate(kem1024.getPublic());

        assertFalse(Arrays.equals(first.getEncapsulation(), second.getEncapsulation()));
        assertFalse(Arrays.equals(first.getEncoded(), second.getEncoded()));
    }

    @Test
    void tamperedCiphertextGivesADifferentSecretAndNoError() throws Exception {
        SecretKeyWithEncapsulation sent = encapsulate(kem1024.getPublic());

        for (int position : new int[] {0, 700, 1567}) {
            byte[] tampered = sent.getEncapsulation().clone();
            tampered[position] ^= 1;

            // FIPS 203 "implicit rejection": no exception that an attacker could use as a signal,
            // just a secret that does not match the sender's.
            byte[] secret = decapsulate(kem1024.getPrivate(), tampered);
            assertEquals(32, secret.length);
            assertFalse(Arrays.equals(sent.getEncoded(), secret));
        }
    }

    @Test
    void wrongPrivateKeyGivesADifferentSecretAndNoError() throws Exception {
        SecretKeyWithEncapsulation sent = encapsulate(kem1024.getPublic());

        byte[] secret = decapsulate(otherKem1024.getPrivate(), sent.getEncapsulation());

        assertFalse(Arrays.equals(sent.getEncoded(), secret));
    }

    @Test
    void ciphertextOfTheWrongLengthIsRejected() throws Exception {
        byte[] ciphertext = encapsulate(kem1024.getPublic()).getEncapsulation();

        // The API must check the length itself and answer 400. Without that check this
        // unchecked exception would surface as a 500 (see SECURITY.md).
        for (int length : new int[] {0, 1, 1088, 1567, 1569, 4096}) {
            byte[] wrongLength = Arrays.copyOf(ciphertext, length);
            assertThrows(IllegalArgumentException.class, () -> decapsulate(kem1024.getPrivate(), wrongLength));
        }
    }

    // ---- ML-KEM: public keys supplied by a client ----

    @Test
    void wellFormedPublicKeySurvivesEncodingAndDecoding() throws Exception {
        byte[] encoded = kem1024.getPublic().getEncoded();

        PublicKey decoded = kemKeyFactory().generatePublic(new X509EncodedKeySpec(encoded));

        assertArrayEquals(encoded, decoded.getEncoded());
        assertEquals("ML-KEM-1024", ((MLKEMPublicKey) decoded).getParameterSpec().getName());
    }

    @Test
    void malformedPublicKeysAreRejected() throws Exception {
        byte[] encoded = kem1024.getPublic().getEncoded();
        byte[] random = new byte[encoded.length];
        RANDOM.nextBytes(random);
        random[0] = 0x04;   // make sure it does not start like a valid key

        assertRejectedPublicKey(Arrays.copyOf(encoded, encoded.length - 1));   // truncated
        assertRejectedPublicKey(Arrays.copyOf(encoded, encoded.length + 1));   // trailing byte
        assertRejectedPublicKey(new byte[0]);
        assertRejectedPublicKey(random);
    }

    @Test
    void publicKeysOfOtherAlgorithmsAreRejected() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);

        assertRejectedPublicKey(rsa.generateKeyPair().getPublic().getEncoded());
        assertRejectedPublicKey(dsa65.getPublic().getEncoded());
    }

    @Test
    void publicKeyWithOutOfRangeValuesIsRejected() {
        // FIPS 203 requires an "encapsulation key check": every coefficient must be below the modulus.
        // Keep the header and the last 32 bytes, overwrite the coefficients with the largest values.
        byte[] encoded = kem1024.getPublic().getEncoded();
        int keyStart = encoded.length - 1568;
        Arrays.fill(encoded, keyStart, encoded.length - 32, (byte) 0xFF);

        assertRejectedPublicKey(encoded);
    }

    @Test
    void weakerParameterSetIsAcceptedByTheGeneralKeyFactory() throws Exception {
        // The general "ML-KEM" key factory happily accepts an ML-KEM-768 key. An API that promises
        // ML-KEM-1024 must not parse client keys with it unchecked, or a client could lower the security level.
        byte[] weakerKey = kem768.getPublic().getEncoded();

        PublicKey decoded = kemKeyFactory().generatePublic(new X509EncodedKeySpec(weakerKey));

        assertEquals("ML-KEM-768", ((MLKEMPublicKey) decoded).getParameterSpec().getName());
    }

    @Test
    void mlKem1024KeyFactoryRejectsWeakerParameterSets() throws Exception {
        // The safe way to parse a client's key: the factory for exactly ML-KEM-1024.
        KeyFactory strict = KeyFactory.getInstance("ML-KEM-1024", CryptoProviders.BC);
        byte[] weakerKey = kem768.getPublic().getEncoded();
        byte[] rightKey = kem1024.getPublic().getEncoded();

        assertThrows(InvalidKeySpecException.class, () -> strict.generatePublic(new X509EncodedKeySpec(weakerKey)));
        assertArrayEquals(rightKey, strict.generatePublic(new X509EncodedKeySpec(rightKey)).getEncoded());
    }

    @Test
    void ciphertextForAWeakerParameterSetDoesNotFit() throws Exception {
        byte[] ciphertext768 = encapsulate(kem768.getPublic()).getEncapsulation();

        assertEquals(1088, ciphertext768.length);
        assertThrows(IllegalArgumentException.class, () -> decapsulate(kem1024.getPrivate(), ciphertext768));
    }

    // ---- ML-KEM: private keys loaded back from storage ----

    @Test
    void storedPrivateKeyStillDecapsulatesAfterReloading() throws Exception {
        SecretKeyWithEncapsulation sent = encapsulate(kem1024.getPublic());
        byte[] stored = kem1024.getPrivate().getEncoded();

        PrivateKey reloaded = kemKeyFactory().generatePrivate(new PKCS8EncodedKeySpec(stored));

        assertArrayEquals(sent.getEncoded(), decapsulate(reloaded, sent.getEncapsulation()));
    }

    @Test
    void corruptedStoredPrivateKeyIsRejected() {
        byte[] stored = kem1024.getPrivate().getEncoded();

        byte[] flippedAtEnd = stored.clone();
        flippedAtEnd[flippedAtEnd.length - 1] ^= 1;
        byte[] flippedInMiddle = stored.clone();
        flippedInMiddle[flippedInMiddle.length / 2] ^= 1;
        byte[] flippedNearStart = stored.clone();
        flippedNearStart[40] ^= 1;

        assertRejectedPrivateKey(flippedAtEnd);
        assertRejectedPrivateKey(flippedInMiddle);
        assertRejectedPrivateKey(flippedNearStart);
        assertRejectedPrivateKey(Arrays.copyOf(stored, stored.length - 1));    // truncated
        assertRejectedPrivateKey(Arrays.copyOf(stored, stored.length + 1));    // trailing byte
        assertRejectedPrivateKey(kem1024.getPublic().getEncoded());            // a public key is not a private key
    }

    // ---- ML-DSA ----

    @Test
    void signatureOverADifferentMessageIsRejected() throws Exception {
        byte[] signature = sign(dsa65.getPrivate(), "pay 10 dollars");

        assertTrue(verify(dsa65.getPublic(), "pay 10 dollars", signature));
        assertFalse(verify(dsa65.getPublic(), "pay 99 dollars", signature));
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        byte[] signature = sign(dsa65.getPrivate(), "QuantumLock");

        for (int position : new int[] {0, 1600, 3308}) {
            byte[] tampered = signature.clone();
            tampered[position] ^= 1;
            assertFalse(verify(dsa65.getPublic(), "QuantumLock", tampered));
        }
    }

    @Test
    void signatureOfTheWrongLengthIsRejectedWithoutAnError() throws Exception {
        byte[] signature = sign(dsa65.getPrivate(), "QuantumLock");

        for (int length : new int[] {0, 3308, 3310}) {
            assertFalse(verify(dsa65.getPublic(), "QuantumLock", Arrays.copyOf(signature, length)));
        }
    }

    @Test
    void signatureFromAnotherKeyIsRejected() throws Exception {
        byte[] signature = sign(otherDsa65.getPrivate(), "QuantumLock");

        assertFalse(verify(dsa65.getPublic(), "QuantumLock", signature));
    }

    @Test
    void keyExchangeKeysCannotBeUsedForSigning() throws Exception {
        Signature signer = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        Signature verifier = Signature.getInstance("ML-DSA", CryptoProviders.BC);

        assertThrows(InvalidKeyException.class, () -> signer.initSign(kem1024.getPrivate()));
        assertThrows(InvalidKeyException.class, () -> verifier.initVerify(kem1024.getPublic()));
    }

    // ---- helpers ----

    private static KeyPair kemKeyPair(MLKEMParameterSpec parameters) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.initialize(parameters, RANDOM);
        return generator.generateKeyPair();
    }

    private static KeyPair dsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-DSA", CryptoProviders.BC);
        generator.initialize(MLDSAParameterSpec.ml_dsa_65, RANDOM);
        return generator.generateKeyPair();
    }

    private static KeyFactory kemKeyFactory() throws Exception {
        return KeyFactory.getInstance("ML-KEM", CryptoProviders.BC);
    }

    private static SecretKeyWithEncapsulation encapsulate(PublicKey publicKey) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.init(new KEMGenerateSpec(publicKey, "AES"), RANDOM);
        return (SecretKeyWithEncapsulation) generator.generateKey();
    }

    private static byte[] decapsulate(PrivateKey privateKey, byte[] ciphertext) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.init(new KEMExtractSpec(privateKey, ciphertext, "AES"));
        return generator.generateKey().getEncoded();
    }

    private static byte[] sign(PrivateKey privateKey, String message) throws Exception {
        Signature signer = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        signer.initSign(privateKey, RANDOM);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return signer.sign();
    }

    private static boolean verify(PublicKey publicKey, String message, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        verifier.initVerify(publicKey);
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    private static void assertRejectedPublicKey(byte[] encoded) {
        assertThrows(InvalidKeySpecException.class,
                () -> kemKeyFactory().generatePublic(new X509EncodedKeySpec(encoded)));
    }

    private static void assertRejectedPrivateKey(byte[] encoded) {
        assertThrows(InvalidKeySpecException.class,
                () -> kemKeyFactory().generatePrivate(new PKCS8EncodedKeySpec(encoded)));
    }
}
