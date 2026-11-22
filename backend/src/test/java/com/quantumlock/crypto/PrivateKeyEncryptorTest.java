package com.quantumlock.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Unit tests for the private-key encryption (task 3.1). Plain JUnit, no Spring context. */
class PrivateKeyEncryptorTest {

    private static final UUID KEY_PAIR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCOUNT = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final PrivateKeyEncryptor encryptor = new PrivateKeyEncryptor(masterKey((byte) 1));
    private final byte[] privateKey = sample(3200);

    @Test
    void decryptGivesBackTheOriginalKey() throws Exception {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);

        assertArrayEquals(privateKey, encryptor.decrypt(stored, KEY_PAIR, ACCOUNT));
    }

    @Test
    void storedValueHasTheDocumentedLayoutAndHidesTheKey() {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);

        assertEquals(1, stored[0]);                                   // format version
        assertEquals(1 + 12 + privateKey.length + 16, stored.length); // version, nonce, ciphertext, tag
        assertFalse(containsSlice(stored, Arrays.copyOf(privateKey, 16)), "the plaintext must not appear");
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        byte[] first = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);
        byte[] second = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);

        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 1, 13), Arrays.copyOfRange(second, 1, 13)));
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void anyChangedByteIsDetected() {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);

        // nonce, start of the ciphertext, middle, authentication tag
        for (int position : new int[] {1, 12, 13, stored.length / 2, stored.length - 1}) {
            byte[] tampered = stored.clone();
            tampered[position] ^= 1;
            assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(tampered, KEY_PAIR, ACCOUNT),
                    "byte " + position);
        }
    }

    @Test
    void ciphertextCannotBeMovedToAnotherKeyPairOrAccount() {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);

        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(stored, UUID.randomUUID(), ACCOUNT));
        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(stored, KEY_PAIR, UUID.randomUUID()));
    }

    @Test
    void anotherMasterKeyCannotDecrypt() {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);
        PrivateKeyEncryptor other = new PrivateKeyEncryptor(masterKey((byte) 2));

        assertThrows(GeneralSecurityException.class, () -> other.decrypt(stored, KEY_PAIR, ACCOUNT));
    }

    @Test
    void malformedStoredValuesAreRejected() {
        byte[] stored = encryptor.encrypt(privateKey, KEY_PAIR, ACCOUNT);
        byte[] otherVersion = stored.clone();
        otherVersion[0] = 2;

        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(null, KEY_PAIR, ACCOUNT));
        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(new byte[0], KEY_PAIR, ACCOUNT));
        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(Arrays.copyOf(stored, 28), KEY_PAIR, ACCOUNT));
        assertThrows(GeneralSecurityException.class, () -> encryptor.decrypt(otherVersion, KEY_PAIR, ACCOUNT));
        assertThrows(GeneralSecurityException.class,
                () -> encryptor.decrypt(Arrays.copyOf(stored, stored.length - 1), KEY_PAIR, ACCOUNT));
    }

    @Test
    void masterKeyMustBe32Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new PrivateKeyEncryptor(null));
        assertThrows(IllegalArgumentException.class, () -> new PrivateKeyEncryptor(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new PrivateKeyEncryptor(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new PrivateKeyEncryptor(new byte[33]));
    }

    @Test
    void idsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(privateKey, null, ACCOUNT));
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(privateKey, KEY_PAIR, null));
    }

    @Test
    void wipeOverwritesTheArray() {
        byte[] secret = sample(64);

        PrivateKeyEncryptor.wipe(secret);
        PrivateKeyEncryptor.wipe(null);

        assertArrayEquals(new byte[64], secret);
    }

    private static byte[] masterKey(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return key;
    }

    private static byte[] sample(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    static boolean containsSlice(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
