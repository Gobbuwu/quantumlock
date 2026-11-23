package com.quantumlock.keys;

import java.time.Instant;
import java.util.UUID;

import com.quantumlock.audit.AuditAction;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every change to the key_pairs table, each in one transaction together with its audit entries: all rows or none.
 */
@Component
public class KeyPairStore {

    private final KeyPairRepository keyPairs;
    private final AuditLogRepository auditLogs;

    public KeyPairStore(KeyPairRepository keyPairs, AuditLogRepository auditLogs) {
        this.keyPairs = keyPairs;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public KeyPairEntity saveNew(KeyPairEntity keyPair, AuditLogEntity auditEntry) {
        KeyPairEntity saved = keyPairs.save(keyPair);
        auditLogs.save(auditEntry);
        return saved;
    }

    /**
     * Rotation (KAN-30): with the old key pair's row locked, saves its replacement, marks it rotated and writes a
     * "rotate" entry for it and a "generate" entry for the replacement. Returns the saved replacement, or null,
     * with nothing written, when the old key pair is not active (any more): for example a rotation that ran at the
     * same moment and won.
     */
    @Transactional
    public KeyPairEntity rotate(UUID accountId, UUID apiKeyId, UUID keyPairId, KeyPairEntity replacement,
            Instant gracePeriodEndsAt) {
        KeyPairEntity old = keyPairs.findForUpdate(keyPairId, accountId).orElse(null);
        if (old == null || !KeyPairEntity.STATUS_ACTIVE.equals(old.getStatus())) {
            return null;
        }
        Instant now = replacement.getCreatedAt();
        // Written first: the old row and the audit entries refer to it.
        KeyPairEntity saved = keyPairs.saveAndFlush(replacement);
        old.markRotated(now, gracePeriodEndsAt, saved.getKeyPairId());
        auditLogs.save(new AuditLogEntity(accountId, apiKeyId, keyPairId, AuditAction.ROTATE, true, now));
        auditLogs.save(new AuditLogEntity(accountId, apiKeyId, saved.getKeyPairId(), AuditAction.GENERATE, true, now));
        return saved;
    }

    /**
     * Revocation (KAN-30): with the row locked, marks an active or rotated key pair revoked and writes a "revoke"
     * entry. Returns it, or null, with nothing written, when it is already revoked.
     */
    @Transactional
    public KeyPairEntity revoke(UUID accountId, UUID apiKeyId, UUID keyPairId, Instant now) {
        KeyPairEntity keyPair = keyPairs.findForUpdate(keyPairId, accountId).orElse(null);
        if (keyPair == null || KeyPairEntity.STATUS_REVOKED.equals(keyPair.getStatus())) {
            return null;
        }
        keyPair.markRevoked();
        auditLogs.save(new AuditLogEntity(accountId, apiKeyId, keyPairId, AuditAction.REVOKE, true, now));
        return keyPair;
    }
}
