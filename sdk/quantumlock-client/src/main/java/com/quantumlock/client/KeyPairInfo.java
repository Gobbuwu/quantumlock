package com.quantumlock.client;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
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
    private final Instant rotatedAt;
    private final Instant gracePeriodEndsAt;
    private final UUID replacedBy;

    KeyPairInfo(UUID keyPairId, Algorithm algorithm, KeyStatus status, byte[] publicKey, Instant createdAt,
            Instant rotatedAt, Instant gracePeriodEndsAt, UUID replacedBy) {
        this.keyPairId = Objects.requireNonNull(keyPairId);
        this.algorithm = Objects.requireNonNull(algorithm);
        this.status = Objects.requireNonNull(status);
        this.publicKey = publicKey.clone();
        this.createdAt = Objects.requireNonNull(createdAt);
        this.rotatedAt = rotatedAt;
        this.gracePeriodEndsAt = gracePeriodEndsAt;
        this.replacedBy = replacedBy;
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

    /** When the key pair was rotated. Empty unless it was. */
    public Optional<Instant> rotatedAt() {
        return Optional.ofNullable(rotatedAt);
    }

    /**
     * Until when a rotated key pair can still decapsulate and verify, so data made for it before can still be read.
     * Empty unless it was rotated.
     */
    public Optional<Instant> gracePeriodEndsAt() {
        return Optional.ofNullable(gracePeriodEndsAt);
    }

    /** The key pair that replaced this one. Empty unless it was rotated. */
    public Optional<UUID> replacedBy() {
        return Optional.ofNullable(replacedBy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyPairInfo that
                && keyPairId.equals(that.keyPairId)
                && algorithm == that.algorithm
                && status == that.status
                && Arrays.equals(publicKey, that.publicKey)
                && createdAt.equals(that.createdAt)
                && Objects.equals(rotatedAt, that.rotatedAt)
                && Objects.equals(gracePeriodEndsAt, that.gracePeriodEndsAt)
                && Objects.equals(replacedBy, that.replacedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyPairId, algorithm, status, Arrays.hashCode(publicKey), createdAt, rotatedAt,
                gracePeriodEndsAt, replacedBy);
    }

    @Override
    public String toString() {
        return "KeyPairInfo[keyPairId=" + keyPairId + ", algorithm=" + algorithm.apiName() + ", status="
                + status.apiName() + ", createdAt=" + createdAt
                + (replacedBy == null ? "" : ", replacedBy=" + replacedBy + ", gracePeriodEndsAt=" + gracePeriodEndsAt)
                + "]";
    }
}
