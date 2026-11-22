package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.KeyGenerator;

import com.quantumlock.api.ApiException;
import com.quantumlock.audit.AuditAction;
import com.quantumlock.audit.AuditLogEntity;
import com.quantumlock.audit.AuditLogRepository;
import com.quantumlock.crypto.CryptoProviders;
import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ML-KEM-1024 key exchange with a stored key pair (task 3.4, use case UC-02 in the RRD).
 *
 * <ul>
 *   <li>Encapsulate: with the key pair's public key, make a fresh shared secret and the ciphertext that carries it.</li>
 *   <li>Decapsulate: with the key pair's private key, get the shared secret back out of a ciphertext.</li>
 * </ul>
 *
 * <p>Rules from SECURITY.md: keys are parsed with the ML-KEM-1024 key factory only (never the general "ML-KEM"
 * one, which also accepts weaker parameter sets); the ciphertext is decoded strictly and its length checked before
 * Bouncy Castle sees it; all bad input gets one fixed 400; a tampered but well-formed ciphertext is answered like
 * any other (FIPS 203 implicit rejection), never with "ciphertext invalid"; every call is audited; no key
 * material or secret is logged.
 */
@Service
public class KeyExchangeService {

    /** FIPS 203, ML-KEM-1024: ciphertext 1568 bytes, shared secret 32 bytes. */
    static final int CIPHERTEXT_BYTES = 1568;
    static final int SHARED_SECRET_BYTES = 32;

    private static final String KEY_FACTORY = "ML-KEM-1024";
    private static final Logger log = LoggerFactory.getLogger(KeyExchangeService.class);

    private final KeyPairRepository keyPairs;
    private final PrivateKeyEncryptor encryptor;
    private final AuditLogRepository auditLogs;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public KeyExchangeService(KeyPairRepository keyPairs, PrivateKeyEncryptor encryptor, AuditLogRepository auditLogs) {
        CryptoProviders.register();
        this.keyPairs = keyPairs;
        this.encryptor = encryptor;
        this.auditLogs = auditLogs;
    }

    /** A new shared secret and its ciphertext. Both arrays belong to the caller. */
    public record Encapsulation(byte[] ciphertext, byte[] sharedSecret) {

        @Override
        public String toString() {
            return "Encapsulation[ciphertext=" + ciphertext.length + " bytes, sharedSecret=hidden]";
        }
    }

    public Encapsulation encapsulate(UUID accountId, UUID apiKeyId, UUID keyPairId) {
        KeyPairEntity keyPair = usableKey(accountId, apiKeyId, keyPairId, AuditAction.ENCAPSULATE);
        try {
            PublicKey publicKey = KeyFactory.getInstance(KEY_FACTORY, CryptoProviders.BC)
                    .generatePublic(new X509EncodedKeySpec(keyPair.getPublicKey()));
            KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
            generator.init(new KEMGenerateSpec(publicKey, "AES"), random);
            SecretKeyWithEncapsulation secret = (SecretKeyWithEncapsulation) generator.generateKey();
            Encapsulation result = new Encapsulation(secret.getEncapsulation(), secret.getEncoded());
            checkSizes(result.ciphertext().length, result.sharedSecret().length);
            audit(accountId, apiKeyId, keyPairId, AuditAction.ENCAPSULATE, true);
            return result;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw failed(accountId, apiKeyId, keyPairId, AuditAction.ENCAPSULATE, e);
        }
    }

    /**
     * The shared secret inside a Base64 ciphertext. A ciphertext that was changed gives a different secret and
     * no error, exactly like a correct one: that is how ML-KEM is designed, and it must stay that way.
     */
    public byte[] decapsulate(UUID accountId, UUID apiKeyId, UUID keyPairId, String ciphertextBase64) {
        KeyPairEntity keyPair = usableKey(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE);
        byte[] ciphertext = decodeCiphertext(ciphertextBase64);
        if (ciphertext == null) {
            audit(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE, false);
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        byte[] encodedPrivateKey = null;
        try {
            encodedPrivateKey = encryptor.decrypt(keyPair.getPrivateKeyCiphertext(), keyPairId, accountId);
            PrivateKey privateKey = KeyFactory.getInstance(KEY_FACTORY, CryptoProviders.BC)
                    .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
            KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
            generator.init(new KEMExtractSpec(privateKey, ciphertext, "AES"));
            byte[] sharedSecret = generator.generateKey().getEncoded();
            checkSizes(CIPHERTEXT_BYTES, sharedSecret.length);
            audit(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE, true);
            return sharedSecret;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw failed(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE, e);
        } finally {
            PrivateKeyEncryptor.wipe(encodedPrivateKey);
        }
    }

    /**
     * Strict Base64 (standard alphabet, with padding, no spaces or line breaks) of exactly 1568 bytes, or null.
     * Re-encoding must give the same text, so there is only one accepted spelling of each ciphertext.
     */
    static byte[] decodeCiphertext(String text) {
        // 1568 bytes are 2092 Base64 characters; anything longer is refused before decoding.
        if (text == null || text.length() != 2092) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length != CIPHERTEXT_BYTES || !Base64.getEncoder().encodeToString(decoded).equals(text)) {
            return null;
        }
        return decoded;
    }

    /** The account's own active ML-KEM-1024 key pair. Every refusal is audited. */
    private KeyPairEntity usableKey(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action) {
        KeyPairEntity keyPair = keyPairs.findByKeyPairIdAndAccountId(keyPairId, accountId).orElse(null);
        if (keyPair == null) {
            // Another team's key and an unknown id look the same. No key id is recorded: it may not exist.
            audit(accountId, apiKeyId, null, action, false);
            throw new ApiException(HttpStatus.NOT_FOUND, "key_not_found");
        }
        if (!Algorithm.ML_KEM_1024.displayName().equals(keyPair.getAlgorithm())) {
            audit(accountId, apiKeyId, keyPairId, action, false);
            throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_key_type");
        }
        if (!KeyPairEntity.STATUS_ACTIVE.equals(keyPair.getStatus())) {
            audit(accountId, apiKeyId, keyPairId, action, false);
            throw new ApiException(HttpStatus.BAD_REQUEST, "key_not_active");
        }
        return keyPair;
    }

    private static void checkSizes(int ciphertextBytes, int sharedSecretBytes) {
        if (ciphertextBytes != CIPHERTEXT_BYTES || sharedSecretBytes != SHARED_SECRET_BYTES) {
            throw new IllegalStateException("Unexpected ML-KEM-1024 output size");
        }
    }

    private ApiException failed(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action, Exception e) {
        // Logged by type only: a message could contain key material.
        log.error("{} failed for key pair {} ({})", action.value(), keyPairId, e.getClass().getSimpleName());
        audit(accountId, apiKeyId, keyPairId, action, false);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "operation_failed");
    }

    private void audit(UUID accountId, UUID apiKeyId, UUID keyPairId, AuditAction action, boolean success) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        auditLogs.save(new AuditLogEntity(accountId, apiKeyId, keyPairId, action, success, now));
    }
}
