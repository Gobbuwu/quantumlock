package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.quantumlock.api.ApiException;
import com.quantumlock.audit.AuditAction;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Key generation and lookup (task 3.2, use case UC-01 in the RRD), rotation and revocation (KAN-30). */
@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private final PostQuantumKeyGenerator generator;
    private final PrivateKeyEncryptor encryptor;
    private final KeyPairStore store;
    private final KeyPairRepository keyPairs;
    private final AuditLogRepository auditLogs;
    private final KeyPairAccess access;
    private final RotationSettings rotation;
    private final Clock clock = Clock.systemUTC();

    KeyService(PostQuantumKeyGenerator generator, PrivateKeyEncryptor encryptor, KeyPairStore store,
            KeyPairRepository keyPairs, AuditLogRepository auditLogs, KeyPairAccess access, RotationSettings rotation) {
        this.generator = generator;
        this.encryptor = encryptor;
        this.store = store;
        this.keyPairs = keyPairs;
        this.auditLogs = auditLogs;
        this.access = access;
        this.rotation = rotation;
    }

    /**
     * Generates a key pair, encrypts the private key, and stores both with a "generate" audit entry.
     * The private key never leaves this method unencrypted: it is not returned and not logged.
     * {@code apiKeyId} is the key the caller used, recorded in the audit entry.
     */
    public KeyPairEntity generate(UUID accountId, UUID apiKeyId, String algorithmName) {
        Algorithm algorithm = Algorithm.fromDisplayName(algorithmName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unsupported_algorithm"));

        UUID keyPairId = UUID.randomUUID();
        Instant now = now();
        KeyPairEntity keyPair;
        try {
            keyPair = create(keyPairId, accountId, algorithm, now);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Logged without any key material: only the algorithm and the type of failure.
            log.error("Key generation failed for {} ({})", algorithm.displayName(), e.getClass().getSimpleName());
            auditLogs.save(new AuditLogEntity(accountId, apiKeyId, null, AuditAction.GENERATE, false, now));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "key_generation_failed");
        }
        return store.saveNew(keyPair,
                new AuditLogEntity(accountId, apiKeyId, keyPairId, AuditAction.GENERATE, true, now));
    }

    /**
     * Replaces an active key pair with a new one of the same algorithm. The old one becomes "rotated": for
     * {@code gracePeriodDays} (null means the default setting) it can still decapsulate and verify, so data made
     * for it before can still be read, then it can do nothing. Returns the new key pair.
     */
    public KeyPairEntity rotate(UUID accountId, UUID apiKeyId, UUID keyPairId, Integer gracePeriodDays) {
        KeyPairEntity old = access.ownKey(accountId, apiKeyId, keyPairId, AuditAction.ROTATE);
        int days = gracePeriodDays == null ? rotation.defaultDays() : gracePeriodDays;
        if (days < 0 || days > rotation.maxDays()) {
            throw access.invalidRequest(accountId, apiKeyId, keyPairId, AuditAction.ROTATE);
        }
        if (!KeyPairEntity.STATUS_ACTIVE.equals(old.getStatus())) {
            throw access.notActive(accountId, apiKeyId, keyPairId, AuditAction.ROTATE);
        }
        Instant now = now();
        KeyPairEntity replacement;
        try {
            // Generated before the row is locked, so the lock is held only for a few quick writes.
            Algorithm algorithm = Algorithm.fromDisplayName(old.getAlgorithm()).orElseThrow(IllegalStateException::new);
            replacement = create(UUID.randomUUID(), accountId, algorithm, now);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.ROTATE, e);
        }
        KeyPairEntity saved;
        try {
            saved = store.rotate(accountId, apiKeyId, keyPairId, replacement, now.plus(Duration.ofDays(days)));
        } catch (RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.ROTATE, e);
        }
        if (saved == null) {
            // Rotated or revoked by another call since the check above. The new key pair was never saved.
            throw access.notActive(accountId, apiKeyId, keyPairId, AuditAction.ROTATE);
        }
        return saved;
    }

    /** Stops an active or rotated key pair for good. The row stays, for the audit trail. Returns it. */
    public KeyPairEntity revoke(UUID accountId, UUID apiKeyId, UUID keyPairId) {
        KeyPairEntity keyPair = access.ownKey(accountId, apiKeyId, keyPairId, AuditAction.REVOKE);
        if (KeyPairEntity.STATUS_REVOKED.equals(keyPair.getStatus())) {
            throw access.notActive(accountId, apiKeyId, keyPairId, AuditAction.REVOKE);
        }
        KeyPairEntity revoked;
        try {
            revoked = store.revoke(accountId, apiKeyId, keyPairId, now());
        } catch (RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.REVOKE, e);
        }
        if (revoked == null) {
            throw access.notActive(accountId, apiKeyId, keyPairId, AuditAction.REVOKE);
        }
        return revoked;
    }

    /** A new key pair with its private key encrypted. The plaintext private key is wiped before this returns. */
    private KeyPairEntity create(UUID keyPairId, UUID accountId, Algorithm algorithm, Instant now)
            throws GeneralSecurityException {
        byte[] privateKey = null;
        try {
            KeyPair generated = generator.generate(algorithm);
            privateKey = generated.getPrivate().getEncoded();
            byte[] ciphertext = encryptor.encrypt(privateKey, keyPairId, accountId);
            return new KeyPairEntity(keyPairId, accountId, algorithm, generated.getPublic().getEncoded(),
                    ciphertext, now);
        } finally {
            PrivateKeyEncryptor.wipe(privateKey);
        }
    }

    public List<KeyPairEntity> list(UUID accountId) {
        return keyPairs.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    /** A key of another account is reported exactly like a key that does not exist. */
    public KeyPairEntity get(UUID accountId, UUID keyPairId) {
        return keyPairs.findByKeyPairIdAndAccountId(keyPairId, accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "key_not_found"));
    }

    /** Microsecond precision, which is what PostgreSQL stores, so a saved time reads back unchanged. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
