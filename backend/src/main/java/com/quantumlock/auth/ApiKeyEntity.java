package com.quantumlock.auth;

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
 * A row of the api_keys table (database/schema.sql). It holds the SHA-256 hash of the key, never the key.
 * The id is chosen by the application, so saving a new key is always an INSERT.
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity implements Persistable<UUID> {

    @Id
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "environment", nullable = false)
    private String environment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Transient
    private boolean isNew = true;

    protected ApiKeyEntity() {
        // for JPA
    }

    ApiKeyEntity(UUID apiKeyId, UUID accountId, String keyHash, String environment, Instant createdAt) {
        this.apiKeyId = apiKeyId;
        this.accountId = accountId;
        this.keyHash = keyHash;
        this.environment = environment;
        this.createdAt = createdAt;
        this.revoked = false;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return apiKeyId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    void revoke() {
        this.revoked = true;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getEnvironment() {
        return environment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isRevoked() {
        return revoked;
    }
}
