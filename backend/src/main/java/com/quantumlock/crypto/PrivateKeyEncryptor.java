package com.quantumlock.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts private keys before they are stored and decrypts them when they are used (task 3.1).
 *
 * <p>AES-256-GCM with a fresh random 12-byte nonce for every encryption. The stored value is:
 * <pre>
 *   1 byte   format version (1)
 *   12 bytes nonce
 *   n bytes  ciphertext followed by the 16-byte authentication tag
 * </pre>
 *
 * <p>The key pair id and the account id are bound to the ciphertext as "additional authenticated data".
 * A ciphertext copied into another row, or into another account, therefore fails to decrypt instead of
 * quietly giving someone else's private key.
 *
 * <p>The 32-byte master key comes from the MASTER_KEY environment variable. It is never stored in the
 * database or the repository.
 */
public final class PrivateKeyEncryptor {

    public static final int MASTER_KEY_BYTES = 32;

    static final byte FORMAT_VERSION = 1;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int MIN_CIPHERTEXT_BYTES = 1 + NONCE_BYTES + TAG_BITS / 8;

    private final SecretKeySpec masterKey;
    private final SecureRandom random;

    public PrivateKeyEncryptor(byte[] masterKey) {
        this(masterKey, new SecureRandom());
    }

    PrivateKeyEncryptor(byte[] masterKey, SecureRandom random) {
        if (masterKey == null || masterKey.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException("The master key must be exactly " + MASTER_KEY_BYTES + " bytes");
        }
        this.masterKey = new SecretKeySpec(masterKey, "AES");
        this.random = random;
    }

    /** Encrypts a PKCS#8 private key for the given key pair and account. */
    public byte[] encrypt(byte[] privateKey, UUID keyPairId, UUID accountId) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(keyPairId, accountId));
            byte[] sealed = cipher.doFinal(privateKey);
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
                    .put(FORMAT_VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value made by {@link #encrypt}. The caller must wipe the returned array
     * ({@code Arrays.fill(result, (byte) 0)}) as soon as the key is no longer needed.
     *
     * @throws GeneralSecurityException if the value was changed, belongs to another key pair or account,
     *                                  or was encrypted with another master key
     */
    public byte[] decrypt(byte[] stored, UUID keyPairId, UUID accountId) throws GeneralSecurityException {
        if (stored == null || stored.length < MIN_CIPHERTEXT_BYTES || stored[0] != FORMAT_VERSION) {
            throw new GeneralSecurityException("Not an encrypted private key in a known format");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
        cipher.updateAAD(associatedData(keyPairId, accountId));
        return cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
    }

    private static byte[] associatedData(UUID keyPairId, UUID accountId) {
        if (keyPairId == null || accountId == null) {
            throw new IllegalArgumentException("Key pair id and account id are required");
        }
        return ("quantumlock/key_pairs/v1/" + keyPairId + "/" + accountId).getBytes(StandardCharsets.US_ASCII);
    }

    /** Overwrites a byte array that held key material. */
    public static void wipe(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
