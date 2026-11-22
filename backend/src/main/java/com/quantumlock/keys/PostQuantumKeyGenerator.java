package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

import com.quantumlock.crypto.CryptoProviders;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.springframework.stereotype.Component;

/** Generates ML-KEM-1024 and ML-DSA-65 key pairs with Bouncy Castle. */
@Component
public class PostQuantumKeyGenerator {

    private final SecureRandom random = new SecureRandom();

    public PostQuantumKeyGenerator() {
        CryptoProviders.register();
    }

    public KeyPair generate(Algorithm algorithm) throws GeneralSecurityException {
        KeyPairGenerator generator;
        switch (algorithm) {
            case ML_KEM_1024 -> {
                generator = KeyPairGenerator.getInstance("ML-KEM", CryptoProviders.BC);
                generator.initialize(MLKEMParameterSpec.ml_kem_1024, random);
            }
            case ML_DSA_65 -> {
                generator = KeyPairGenerator.getInstance("ML-DSA", CryptoProviders.BC);
                generator.initialize(MLDSAParameterSpec.ml_dsa_65, random);
            }
            default -> throw new IllegalArgumentException("Unsupported algorithm");
        }
        return generator.generateKeyPair();
    }
}
