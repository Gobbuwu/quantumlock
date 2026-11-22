package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Clock;
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

/** Key generation and lookup (task 3.2, use case UC-01 in the RRD). */
@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private final PostQuantumKeyGenerator generator;
    private final PrivateKeyEncryptor encryptor;
    private final KeyPairStore store;
    private final KeyPairRepository keyPairs;
    private final AuditLogRepository auditLogs;
    private final Clock clock = Clock.systemUTC();

    public KeyService(PostQuantumKeyGenerator generator, PrivateKeyEncryptor encryptor, KeyPairStore store,
            KeyPairRepository keyPairs, AuditLogRepository auditLogs) {
        this.generator = generator;
        this.encryptor = encryptor;
        this.store = store;
        this.keyPairs = keyPairs;
        this.auditLogs = auditLogs;
    }

    /**
     * Generates a key pair, encrypts the private key, and stores both with a "generate" audit entry.
     * The private key never leaves this method unencrypted: it is not returned and not logged.
     */
    public KeyPairEntity generate(UUID accountId, String algorithmName) {
        Algorithm algorithm = Algorithm.fromDisplayName(algorithmName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unsupported_algorithm"));

        UUID keyPairId = UUID.randomUUID();
        Instant now = now();
        byte[] privateKey = null;
        KeyPairEntity keyPair;
        try {
            KeyPair generated = generator.generate(algorithm);
            privateKey = generated.getPrivate().getEncoded();
            byte[] ciphertext = encryptor.encrypt(privateKey, keyPairId, accountId);
            keyPair = new KeyPairEntity(keyPairId, accountId, algorithm, generated.getPublic().getEncoded(),
                    ciphertext, now);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Logged without any key material: only the algorithm and the type of failure.
            log.error("Key generation failed for {} ({})", algorithm.displayName(), e.getClass().getSimpleName());
            auditLogs.save(new AuditLogEntity(accountId, null, null, AuditAction.GENERATE, false, now));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "key_generation_failed");
        } finally {
            PrivateKeyEncryptor.wipe(privateKey);
        }
        return store.saveNew(keyPair,
                new AuditLogEntity(accountId, null, keyPairId, AuditAction.GENERATE, true, now));
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
