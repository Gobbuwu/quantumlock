package com.quantumlock.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;

import javax.crypto.KeyGenerator;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the Bouncy Castle version in pom.xml supports the algorithms the
 * key features rely on (tasks 3.2 and 3.4). Plain JUnit, no Spring context.
 */
class PostQuantumSmokeTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeAll
    static void registerProvider() {
        CryptoProviders.register();
    }

    @Test
    void mlKem1024EncapsulateThenDecapsulateGivesSameSecret() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        kpg.initialize(MLKEMParameterSpec.ml_kem_1024, RANDOM);
        KeyPair keyPair = kpg.generateKeyPair();

        // Sender: encapsulate against the public key -> shared secret + ciphertext ("encapsulation")
        KeyGenerator sender = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        sender.init(new KEMGenerateSpec(keyPair.getPublic(), "AES"), RANDOM);
        SecretKeyWithEncapsulation senderSecret = (SecretKeyWithEncapsulation) sender.generateKey();

        // Receiver: decapsulate the ciphertext with the private key -> the same shared secret
        KeyGenerator receiver = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        receiver.init(new KEMExtractSpec(keyPair.getPrivate(), senderSecret.getEncapsulation(), "AES"));
        SecretKeyWithEncapsulation receiverSecret = (SecretKeyWithEncapsulation) receiver.generateKey();

        assertEquals(1568, senderSecret.getEncapsulation().length);   // ML-KEM-1024 ciphertext size (FIPS 203)
        assertEquals(32, senderSecret.getEncoded().length);           // shared secret size (FIPS 203)
        assertArrayEquals(senderSecret.getEncoded(), receiverSecret.getEncoded());
    }

    @Test
    void mlDsa65SignatureVerifies() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-DSA", CryptoProviders.BC);
        kpg.initialize(MLDSAParameterSpec.ml_dsa_65, RANDOM);
        KeyPair keyPair = kpg.generateKeyPair();
        byte[] message = "QuantumLock".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        signer.initSign(keyPair.getPrivate(), RANDOM);
        signer.update(message);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("ML-DSA", CryptoProviders.BC);
        verifier.initVerify(keyPair.getPublic());
        verifier.update(message);

        assertEquals(3309, signature.length);                         // ML-DSA-65 signature size (FIPS 204)
        assertTrue(verifier.verify(signature));
    }
}
