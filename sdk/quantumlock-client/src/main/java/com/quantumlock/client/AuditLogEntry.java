package com.quantumlock.client;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One row of the audit log. The log holds no messages, secrets or key material. */
public final class AuditLogEntry {

    private final String id;
    private final Instant time;
    private final String action;
    private final boolean success;
    private final UUID keyPairId;
    private final UUID apiKeyId;

    AuditLogEntry(String id, Instant time, String action, boolean success, UUID keyPairId, UUID apiKeyId) {
        this.id = Objects.requireNonNull(id);
        this.time = Objects.requireNonNull(time);
        this.action = Objects.requireNonNull(action);
        this.success = success;
        this.keyPairId = keyPairId;
        this.apiKeyId = apiKeyId;
    }

    /** The row's id. */
    public String id() {
        return id;
    }

    /** When it happened. */
    public Instant time() {
        return time;
    }

    /** "generate", "encapsulate", "decapsulate", "sign", "verify", "rotate" or "revoke". */
    public String action() {
        return action;
    }

    /** True when the operation succeeded. A verify that found a signature invalid still succeeded. */
    public boolean success() {
        return success;
    }

    /** The key pair it was about. Empty when the request named no key pair of this team. */
    public Optional<UUID> keyPairId() {
        return Optional.ofNullable(keyPairId);
    }

    /** The API key that made the request. Empty for rows written before API keys existed. */
    public Optional<UUID> apiKeyId() {
        return Optional.ofNullable(apiKeyId);
    }

    @Override
    public String toString() {
        return "AuditLogEntry[id=" + id + ", time=" + time + ", action=" + action + ", success=" + success
                + ", keyPairId=" + keyPairId + "]";
    }
}
