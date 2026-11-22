package com.quantumlock.client;

import java.util.UUID;

/**
 * Result of encapsulate: send {@link #ciphertext()} to the key pair's owner and keep the
 * {@link #sharedSecret()} to yourself. Closing it destroys the shared secret.
 */
public final class Encapsulation implements AutoCloseable {

    /** Length of an ML-KEM-1024 ciphertext in bytes. */
    public static final int CIPHERTEXT_LENGTH = 1568;

    private final UUID keyPairId;
    private final byte[] ciphertext;
    private final SharedSecret sharedSecret;

    Encapsulation(UUID keyPairId, byte[] ciphertext, SharedSecret sharedSecret) {
        if (ciphertext.length != CIPHERTEXT_LENGTH) {
            throw new QuantumLockResponseException("ciphertext has the wrong length");
        }
        this.keyPairId = keyPairId;
        this.ciphertext = ciphertext.clone();
        this.sharedSecret = sharedSecret;
    }

    public UUID keyPairId() {
        return keyPairId;
    }

    /** The 1568-byte ML-KEM-1024 ciphertext. Not secret. A new copy on every call. */
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    public SharedSecret sharedSecret() {
        return sharedSecret;
    }

    @Override
    public void close() {
        sharedSecret.destroy();
    }

    @Override
    public String toString() {
        return "Encapsulation[keyPairId=" + keyPairId + ", sharedSecret=hidden]";
    }
}
