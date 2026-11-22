package com.quantumlock.client;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * What the API says about a key pair. It never contains the private key: the private key stays on the
 * QuantumLock server, stored encrypted.
 */
public final class KeyPairInfo {

    private final UUID keyPairId;
    private final Algorithm algorithm;
    private final KeyStatus status;
    private final byte[] publicKey;
    private final Instant createdAt;

    KeyPairInfo(UUID keyPairId, Algorithm algorithm, KeyStatus status, byte[] publicKey, Instant createdAt) {
        this.keyPairId = Objects.requireNonNull(keyPairId);
        this.algorithm = Objects.requireNonNull(algorithm);
        this.status = Objects.requireNonNull(status);
        this.publicKey = publicKey.clone();
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** The id to use in later calls. */
    public UUID keyPairId() {
        return keyPairId;
    }

    public Algorithm algorithm() {
        return algorithm;
    }

    public KeyStatus status() {
        return status;
    }

    /** The public key, X.509 SubjectPublicKeyInfo encoded. A new copy on every call. */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyPairInfo that
                && keyPairId.equals(that.keyPairId)
                && algorithm == that.algorithm
                && status == that.status
                && Arrays.equals(publicKey, that.publicKey)
                && createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyPairId, algorithm, status, Arrays.hashCode(publicKey), createdAt);
    }

    @Override
    public String toString() {
        return "KeyPairInfo[keyPairId=" + keyPairId + ", algorithm=" + algorithm.apiName() + ", status="
                + status.apiName() + ", createdAt=" + createdAt + "]";
    }
}
