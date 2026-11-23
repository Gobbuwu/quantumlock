package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import javax.crypto.KeyGenerator;

import com.quantumlock.audit.AuditAction;
import com.quantumlock.crypto.CryptoProviders;
import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
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

    private final KeyPairAccess access;
    private final PrivateKeyEncryptor encryptor;
    private final SecureRandom random = new SecureRandom();

    static {
        // In a static block so the static encapsulate and decapsulate helpers can rely on it too.
        CryptoProviders.register();
    }

    KeyExchangeService(KeyPairAccess access, PrivateKeyEncryptor encryptor) {
        this.access = access;
        this.encryptor = encryptor;
    }

    /** A new shared secret and its ciphertext. Both arrays belong to the caller. */
    public record Encapsulation(byte[] ciphertext, byte[] sharedSecret) {

        @Override
        public String toString() {
            return "Encapsulation[ciphertext=" + ciphertext.length + " bytes, sharedSecret=hidden]";
        }
    }

    public Encapsulation encapsulate(UUID accountId, UUID apiKeyId, UUID keyPairId) {
        KeyPairEntity keyPair = access.usableKey(accountId, apiKeyId, keyPairId, Algorithm.ML_KEM_1024,
                AuditAction.ENCAPSULATE);
        try {
            Encapsulation result = encapsulate(keyPair.getPublicKey(), random);
            access.audit(accountId, apiKeyId, keyPairId, AuditAction.ENCAPSULATE, true);
            return result;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.ENCAPSULATE, e);
        }
    }

    /**
     * The shared secret inside a Base64 ciphertext. A ciphertext that was changed gives a different secret and
     * no error, exactly like a correct one: that is how ML-KEM is designed, and it must stay that way.
     */
    public byte[] decapsulate(UUID accountId, UUID apiKeyId, UUID keyPairId, String ciphertextBase64) {
        KeyPairEntity keyPair = access.usableKey(accountId, apiKeyId, keyPairId, Algorithm.ML_KEM_1024,
                AuditAction.DECAPSULATE);
        byte[] ciphertext = decodeCiphertext(ciphertextBase64);
        if (ciphertext == null) {
            throw access.invalidRequest(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE);
        }
        byte[] encodedPrivateKey = null;
        try {
            encodedPrivateKey = encryptor.decrypt(keyPair.getPrivateKeyCiphertext(), keyPairId, accountId);
            byte[] sharedSecret = decapsulate(encodedPrivateKey, ciphertext);
            access.audit(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE, true);
            return sharedSecret;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.DECAPSULATE, e);
        } finally {
            PrivateKeyEncryptor.wipe(encodedPrivateKey);
        }
    }

    /**
     * ML-KEM-1024 encapsulation (FIPS 203 ML-KEM.Encaps) with an X.509-encoded public key.
     * The shared secret is the FIPS 203 output K itself. Bouncy Castle's default would pass K through
     * an extra key derivation function, which no other FIPS 203 implementation does, so a client that
     * encapsulates on its own computer would get a different secret. withNoKdf() prevents that.
     */
    static Encapsulation encapsulate(byte[] x509PublicKey, SecureRandom random) throws GeneralSecurityException {
        PublicKey publicKey = KeyFactory.getInstance(KEY_FACTORY, CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(x509PublicKey));
        KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.init(new KEMGenerateSpec.Builder(publicKey, "AES", SHARED_SECRET_BYTES * 8).withNoKdf().build(), random);
        SecretKeyWithEncapsulation secret = (SecretKeyWithEncapsulation) generator.generateKey();
        Encapsulation result = new Encapsulation(secret.getEncapsulation(), secret.getEncoded());
        checkSizes(result.ciphertext().length, result.sharedSecret().length);
        return result;
    }

    /**
     * ML-KEM-1024 decapsulation (FIPS 203 ML-KEM.Decaps) with a PKCS#8-encoded private key. Returns K
     * with no extra key derivation, matching {@link #encapsulate(byte[], SecureRandom)}. A tampered
     * ciphertext gives an unrelated secret (implicit rejection), not an error.
     */
    static byte[] decapsulate(byte[] pkcs8PrivateKey, byte[] ciphertext) throws GeneralSecurityException {
        PrivateKey privateKey = KeyFactory.getInstance(KEY_FACTORY, CryptoProviders.BC)
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8PrivateKey));
        KeyGenerator generator = KeyGenerator.getInstance("ML-KEM", CryptoProviders.BC);
        generator.init(new KEMExtractSpec.Builder(privateKey, ciphertext, "AES", SHARED_SECRET_BYTES * 8).withNoKdf().build());
        byte[] sharedSecret = generator.generateKey().getEncoded();
        checkSizes(CIPHERTEXT_BYTES, sharedSecret.length);
        return sharedSecret;
    }

    /**
     * Strict Base64 (standard alphabet, with padding, no spaces or line breaks) of exactly 1568 bytes, or null.
     * Re-encoding must give the same text, so there is only one accepted spelling of each ciphertext.
     */
    static byte[] decodeCiphertext(String text) {
        return StrictBase64.decode(text, CIPHERTEXT_BYTES, CIPHERTEXT_BYTES);
    }

    private static void checkSizes(int ciphertextBytes, int sharedSecretBytes) {
        if (ciphertextBytes != CIPHERTEXT_BYTES || sharedSecretBytes != SHARED_SECRET_BYTES) {
            throw new IllegalStateException("Unexpected ML-KEM-1024 output size");
        }
    }
}
