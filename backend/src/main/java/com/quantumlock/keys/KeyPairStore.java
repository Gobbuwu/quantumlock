package com.quantumlock.keys;

import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Saves a new key pair together with its audit entry, in one transaction: both rows or neither. */
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
}
