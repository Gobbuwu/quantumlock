package com.quantumlock.audit;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One audit log row as the API returns it (KAN-32). The log holds no messages, secrets or key material.
 *
 * @param id        the row's id, also the cursor for the next page
 * @param time      when it happened, ISO-8601 in UTC
 * @param action    "generate", "encapsulate", "decapsulate", "sign", "verify", "rotate" or "revoke"
 * @param status    "success" or "failure"
 * @param keyPairId the key pair it was about, or null when the request named no key pair of this team
 * @param apiKeyId  the API key that made the request, or null for rows written before API keys existed
 */
public record AuditLogEntry(
        String id,
        String time,
        String action,
        String status,
        @Schema(nullable = true) String keyPairId,
        @Schema(nullable = true) String apiKeyId) {

    static AuditLogEntry from(AuditLogEntity row) {
        return new AuditLogEntry(
                row.getLogId().toString(),
                row.getCreatedAt().toString(),
                row.getAction(),
                row.getStatus(),
                row.getKeyPairId() == null ? null : row.getKeyPairId().toString(),
                row.getApiKeyId() == null ? null : row.getApiKeyId().toString());
    }
}
