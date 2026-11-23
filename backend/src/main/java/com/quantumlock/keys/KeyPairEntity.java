package com.quantumlock.keys;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * A row of the key_pairs table (database/schema.sql).
 *
 * <p>The id is chosen by the application (it is bound into the private key encryption, see
 * PrivateKeyEncryptor), so the entity tells Spring Data itself whether it is new. Saving a new key
 * pair is then always an INSERT and can never overwrite an existing row.
 */
@Entity
@Table(name = "key_pairs")
public class KeyPairEntity implements Persistable<UUID> {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ROTATED = "rotated";
    public static final String STATUS_REVOKED = "revoked";

    @Id
    @Column(name = "key_pair_id")
    private UUID keyPairId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "private_key_ciphertext", nullable = false)
    private byte[] privateKeyCiphertext;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "grace_period_ends_at")
    private Instant gracePeriodEndsAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Transient
    private boolean isNew = true;

    protected KeyPairEntity() {
        // for JPA
    }

    public KeyPairEntity(UUID keyPairId, UUID accountId, Algorithm algorithm, byte[] publicKey,
            byte[] privateKeyCiphertext, Instant createdAt) {
        this.keyPairId = keyPairId;
        this.accountId = accountId;
        this.algorithm = algorithm.displayName();
        this.publicKey = publicKey;
        this.privateKeyCiphertext = privateKeyCiphertext;
        this.status = STATUS_ACTIVE;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return keyPairId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getKeyPairId() {
        return keyPairId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public byte[] getPrivateKeyCiphertext() {
        return privateKeyCiphertext;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public Instant getGracePeriodEndsAt() {
        return gracePeriodEndsAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    /** Replaced by {@code replacement}; still usable to decapsulate and verify until {@code gracePeriodEndsAt}. */
    void markRotated(Instant rotatedAt, Instant gracePeriodEndsAt, UUID replacement) {
        this.status = STATUS_ROTATED;
        this.rotatedAt = rotatedAt;
        this.gracePeriodEndsAt = gracePeriodEndsAt;
        this.replacedBy = replacement;
    }

    /** Unusable for good. The rotation details, if any, are kept for the record. */
    void markRevoked() {
        this.status = STATUS_REVOKED;
    }

    /** Rotated and the grace period has not ended at {@code now}. */
    boolean inGracePeriod(Instant now) {
        return STATUS_ROTATED.equals(status) && gracePeriodEndsAt != null && now.isBefore(gracePeriodEndsAt);
    }
}
