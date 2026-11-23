package com.quantumlock.keys;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.quantumlock.api.ApiException;
import com.quantumlock.audit.AuditAction;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The rules every operation on a stored key pair shares (key exchange, signatures): the key pair must belong to the
 * caller's team, have the right algorithm and be active, every refusal and every result is audited, and failures are
 * logged without any key material.
 */
@Component
class KeyPairAccess {

    private static final Logger log = LoggerFactory.getLogger(KeyPairAccess.class);

    private final KeyPairRepository keyPairs;
    private final AuditLogRepository auditLogs;
    private final Clock clock = Clock.systemUTC();

    KeyPairAccess(KeyPairRepository keyPairs, AuditLogRepository auditLogs) {
        this.keyPairs = keyPairs;
        this.auditLogs = auditLogs;
    }

    /** The team's own active key pair of the given algorithm. Every refusal is audited. */
    KeyPairEntity usableKey(UUID accountId, UUID apiKeyId, UUID keyPairId, Algorithm algorithm, AuditAction action) {
        KeyPairEntity keyPair = keyPairs.findByKeyPairIdAndAccountId(keyPairId, accountId).orElse(null);
        if (keyPair == null) {
            // Another team's key and an unknown id look the same. No key id is recorded: it may not exist.
            audit(accountId, apiKeyId, null, action, false);
            throw new ApiException(HttpStatus.NOT_FOUND, "key_not_found");
        }
        if (!algorithm.displayName().equals(keyPair.getAlgorithm())) {
            audit(accountId, apiKeyId, keyPairId, action, false);
            throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_key_type");
        }
        if (!KeyPairEntity.STATUS_ACTIVE.equals(keyPair.getStatus())) {
            audit(accountId, apiKeyId, keyPairId, action, false);
            throw new ApiException(HttpStatus.BAD_REQUEST, "key_not_active");
        }
        return keyPair;
    }

    /** Audits a refused input and returns the one fixed answer for it. */
    ApiException invalidRequest(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action) {
        audit(accountId, apiKeyId, keyPairId, action, false);
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /** Logs and audits an unexpected failure and returns the one fixed answer for it. */
    ApiException failed(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action, Exception e) {
        // Logged by type only: a message could contain key material.
        log.error("{} failed for key pair {} ({})", action.value(), keyPairId, e.getClass().getSimpleName());
        audit(accountId, apiKeyId, keyPairId, action, false);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "operation_failed");
    }

    void audit(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action, boolean success) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        auditLogs.save(new AuditLogEntity(accountId, apiKeyId, keyPairId, action, success, now));
    }
}
