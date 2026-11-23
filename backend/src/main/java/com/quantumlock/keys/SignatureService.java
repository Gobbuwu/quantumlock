package com.quantumlock.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import com.quantumlock.audit.AuditAction;
import com.quantumlock.crypto.CryptoProviders;
import com.quantumlock.crypto.PrivateKeyEncryptor;
import org.bouncycastle.jcajce.spec.ContextParameterSpec;
import org.springframework.stereotype.Service;

/**
 * ML-DSA-65 signatures with a stored key pair (FIPS 204, pure ML-DSA with an optional context string).
 *
 * <ul>
 *   <li>Sign: with the key pair's private key. Hedged (randomized) signing, the FIPS 204 default.</li>
 *   <li>Verify: with the key pair's public key only. A signature that does not match is an answer (valid: false),
 *       not an error.</li>
 * </ul>
 *
 * <p>Same rules as key exchange: keys are parsed with the ML-DSA-65 key factory only (never the general "ML-DSA"
 * one, which also accepts other parameter sets); every input is decoded strictly and its length checked before
 * Bouncy Castle sees it; bad input gets one fixed 400; every call is audited; no message, signature or key material
 * is stored or logged.
 */
@Service
public class SignatureService {

    /** FIPS 204, ML-DSA-65: signature 3309 bytes. */
    static final int SIGNATURE_BYTES = 3309;
    /** Leaves room for the message, signature and context in one request under the 64 KiB body limit. */
    static final int MAX_MESSAGE_BYTES = 32 * 1024;
    /** FIPS 204: the context string is at most 255 bytes. */
    static final int MAX_CONTEXT_BYTES = 255;

    private static final String ALGORITHM = "ML-DSA-65";

    private final KeyPairAccess access;
    private final PrivateKeyEncryptor encryptor;
    private final SecureRandom random = new SecureRandom();

    static {
        CryptoProviders.register();
    }

    SignatureService(KeyPairAccess access, PrivateKeyEncryptor encryptor) {
        this.access = access;
        this.encryptor = encryptor;
    }

    /** Signs a Base64 message, with an optional Base64 context (null means none). Returns the signature bytes. */
    public byte[] sign(UUID accountId, UUID apiKeyId, UUID keyPairId, String messageBase64, String contextBase64) {
        KeyPairEntity keyPair = access.usableKey(accountId, apiKeyId, keyPairId, Algorithm.ML_DSA_65, AuditAction.SIGN);
        byte[] message = decodeMessage(messageBase64);
        byte[] context = decodeContext(contextBase64);
        if (message == null || context == null) {
            throw access.invalidRequest(accountId, apiKeyId, keyPairId, AuditAction.SIGN);
        }
        byte[] encodedPrivateKey = null;
        try {
            encodedPrivateKey = encryptor.decrypt(keyPair.getPrivateKeyCiphertext(), keyPairId, accountId);
            byte[] signature = sign(encodedPrivateKey, message, context, random);
            access.audit(accountId, apiKeyId, keyPairId, AuditAction.SIGN, true);
            return signature;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.SIGN, e);
        } finally {
            PrivateKeyEncryptor.wipe(encodedPrivateKey);
        }
    }

    /** True when the Base64 signature is valid for the message and context under the key pair's public key. */
    public boolean verify(UUID accountId, UUID apiKeyId, UUID keyPairId, String messageBase64, String signatureBase64,
            String contextBase64) {
        KeyPairEntity keyPair = access.usableKey(accountId, apiKeyId, keyPairId, Algorithm.ML_DSA_65,
                AuditAction.VERIFY);
        byte[] message = decodeMessage(messageBase64);
        byte[] signature = StrictBase64.decode(signatureBase64, SIGNATURE_BYTES, SIGNATURE_BYTES);
        byte[] context = decodeContext(contextBase64);
        if (message == null || signature == null || context == null) {
            throw access.invalidRequest(accountId, apiKeyId, keyPairId, AuditAction.VERIFY);
        }
        try {
            boolean valid = verify(keyPair.getPublicKey(), message, context, signature);
            // The check itself succeeded, whatever its answer: the audit status records that, not the answer.
            access.audit(accountId, apiKeyId, keyPairId, AuditAction.VERIFY, true);
            return valid;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw access.failed(accountId, apiKeyId, keyPairId, AuditAction.VERIFY, e);
        }
    }

    /**
     * FIPS 204 ML-DSA.Sign (pure, external interface) with a PKCS#8-encoded ML-DSA-65 private key. The random
     * source supplies the 32-byte rnd; a source of 32 zero bytes gives the deterministic variant.
     */
    static byte[] sign(byte[] pkcs8PrivateKey, byte[] message, byte[] context, SecureRandom random)
            throws GeneralSecurityException {
        PrivateKey privateKey = KeyFactory.getInstance(ALGORITHM, CryptoProviders.BC)
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8PrivateKey));
        Signature signer = Signature.getInstance(ALGORITHM, CryptoProviders.BC);
        signer.initSign(privateKey, random);
        signer.setParameter(new ContextParameterSpec(context));
        signer.update(message);
        byte[] signature = signer.sign();
        if (signature.length != SIGNATURE_BYTES) {
            throw new IllegalStateException("Unexpected ML-DSA-65 signature size");
        }
        return signature;
    }

    /** FIPS 204 ML-DSA.Verify (pure, external interface) with an X.509-encoded ML-DSA-65 public key. */
    static boolean verify(byte[] x509PublicKey, byte[] message, byte[] context, byte[] signature)
            throws GeneralSecurityException {
        PublicKey publicKey = KeyFactory.getInstance(ALGORITHM, CryptoProviders.BC)
                .generatePublic(new X509EncodedKeySpec(x509PublicKey));
        Signature verifier = Signature.getInstance(ALGORITHM, CryptoProviders.BC);
        verifier.initVerify(publicKey);
        verifier.setParameter(new ContextParameterSpec(context));
        verifier.update(message);
        return verifier.verify(signature);
    }

    /** 1 to 32 KiB of strict Base64, or null. */
    static byte[] decodeMessage(String text) {
        return StrictBase64.decode(text, 1, MAX_MESSAGE_BYTES);
    }

    /** No context (null) means the empty context. Otherwise 0 to 255 bytes of strict Base64, or null. */
    static byte[] decodeContext(String text) {
        return text == null ? new byte[0] : StrictBase64.decode(text, 0, MAX_CONTEXT_BYTES);
    }
}
