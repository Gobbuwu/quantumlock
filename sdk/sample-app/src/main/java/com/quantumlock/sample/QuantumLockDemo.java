package com.quantumlock.sample;

import java.nio.charset.StandardCharsets;

import com.quantumlock.client.Algorithm;
import com.quantumlock.client.Encapsulation;
import com.quantumlock.client.KeyPairInfo;
import com.quantumlock.client.QuantumLockClient;
import com.quantumlock.client.QuantumLockException;
import com.quantumlock.client.SharedSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The whole demo: a key exchange with ML-KEM-1024, then a signature with ML-DSA-65. The QuantumLockClient is ready
 * to use: the starter made it from two settings. Nothing secret is printed, only ids, sizes and yes/no answers.
 */
@Component
class QuantumLockDemo implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(QuantumLockDemo.class);

    static final byte[] MESSAGE = "Invoice 2026-118: pay 1,250.00 to account 42".getBytes(StandardCharsets.UTF_8);
    static final byte[] CHANGED_MESSAGE = "Invoice 2026-118: pay 9,250.00 to account 42".getBytes(StandardCharsets.UTF_8);
    /** Binds the signature to one purpose: a signature made for invoices does not verify as anything else. */
    static final byte[] CONTEXT = "invoices".getBytes(StandardCharsets.UTF_8);

    private final QuantumLockClient quantumLock;
    private int exitCode = 1;

    QuantumLockDemo(QuantumLockClient quantumLock) {
        this.quantumLock = quantumLock;
    }

    @Override
    public void run(String... args) {
        try {
            exitCode = keyExchange() && signature() ? 0 : 1;
        } catch (QuantumLockException e) {
            // The SDK's messages never contain the API key or a secret.
            log.error("QuantumLock call failed: {}", e.getMessage());
            exitCode = 1;
        }
    }

    /** True when both sides end up with the same shared secret. */
    private boolean keyExchange() {
        // 1. A new ML-KEM-1024 key pair. Its private key never leaves the QuantumLock server.
        KeyPairInfo keyPair = quantumLock.generateKeyPair(Algorithm.ML_KEM_1024);
        log.info("Generated {} key pair {}", keyPair.algorithm().apiName(), keyPair.keyPairId());

        // 2. The sender makes a shared secret and a ciphertext; 3. the owner gets the secret out of the ciphertext.
        try (Encapsulation sent = quantumLock.encapsulate(keyPair.keyPairId());
             SharedSecret received = quantumLock.decapsulate(keyPair.keyPairId(), sent.ciphertext())) {
            boolean match = sent.sharedSecret().matches(received);
            log.info("Ciphertext: {} bytes. Shared secrets match: {}", sent.ciphertext().length, match);
            return match;
        }
    }

    /** True when the signature verifies and the same signature on a changed message does not. */
    private boolean signature() {
        // 4. A new ML-DSA-65 key pair, 5. a signature on a message, 6. two checks of that signature.
        KeyPairInfo keyPair = quantumLock.generateKeyPair(Algorithm.ML_DSA_65);
        log.info("Generated {} key pair {}", keyPair.algorithm().apiName(), keyPair.keyPairId());

        byte[] signature = quantumLock.sign(keyPair.keyPairId(), MESSAGE, CONTEXT);
        boolean valid = quantumLock.verify(keyPair.keyPairId(), MESSAGE, signature, CONTEXT);
        boolean changedAccepted = quantumLock.verify(keyPair.keyPairId(), CHANGED_MESSAGE, signature, CONTEXT);
        log.info("Signature: {} bytes. Signature valid: {}. Changed message accepted: {}", signature.length, valid,
                changedAccepted);
        return valid && !changedAccepted;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
