package com.quantumlock.sample;

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
 * The whole demo. The QuantumLockClient is ready to use: the starter made it from two settings.
 * Nothing secret is printed, only ids and whether the two secrets match.
 */
@Component
class KeyExchangeDemo implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(KeyExchangeDemo.class);

    private final QuantumLockClient quantumLock;
    private int exitCode = 1;

    KeyExchangeDemo(QuantumLockClient quantumLock) {
        this.quantumLock = quantumLock;
    }

    @Override
    public void run(String... args) {
        try {
            // 1. A new ML-KEM-1024 key pair. Its private key never leaves the QuantumLock server.
            KeyPairInfo keyPair = quantumLock.generateKeyPair(Algorithm.ML_KEM_1024);
            log.info("Generated {} key pair {}", keyPair.algorithm().apiName(), keyPair.keyPairId());

            // 2. The sender makes a shared secret and a ciphertext; 3. the owner gets the secret out of the ciphertext.
            try (Encapsulation sent = quantumLock.encapsulate(keyPair.keyPairId());
                 SharedSecret received = quantumLock.decapsulate(keyPair.keyPairId(), sent.ciphertext())) {
                boolean match = sent.sharedSecret().matches(received);
                log.info("Ciphertext: {} bytes. Shared secrets match: {}", sent.ciphertext().length, match);
                exitCode = match ? 0 : 1;
            }
        } catch (QuantumLockException e) {
            // The SDK's messages never contain the API key or a secret.
            log.error("QuantumLock call failed: {}", e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
