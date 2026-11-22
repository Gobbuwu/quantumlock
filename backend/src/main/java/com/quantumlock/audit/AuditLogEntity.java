package com.quantumlock.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the audit_logs table (database/schema.sql). Holds no key material, only who did what to which key. */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "key_pair_id")
    private UUID keyPairId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
        // for JPA
    }

    public AuditLogEntity(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action, boolean success,
            Instant createdAt) {
        this.accountId = accountId;
        this.apiKeyId = apiKeyId;
        this.keyPairId = keyPairId;
        this.action = action.value();
        this.status = success ? "success" : "failure";
        this.createdAt = createdAt;
    }

    public Long getLogId() {
        return logId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public UUID getKeyPairId() {
        return keyPairId;
    }

    public String getAction() {
        return action;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
