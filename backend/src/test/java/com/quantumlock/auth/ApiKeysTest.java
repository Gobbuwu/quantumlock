package com.quantumlock.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Key format, randomness and hashing (task 3.3). Plain unit tests, no Spring context. */
class ApiKeysTest {

    @Test
    void keyCarries32RandomBytesAfterItsPrefix() {
        String key = ApiKeys.newKey("test");

        assertTrue(key.startsWith("ql_test_"));
        byte[] random = Base64.getUrlDecoder().decode(key.substring("ql_test_".length()));
        assertEquals(32, random.length);
        assertTrue(ApiKeys.hasValidFormat(key));
        assertTrue(ApiKeys.hasValidFormat(ApiKeys.newKey("live")));
    }

    @Test
    void keysDoNotRepeat() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(keys.add(ApiKeys.newKey("test")));
        }
    }

    @Test
    void unknownEnvironmentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeys.newKey("prod"));
    }

    @Test
    void malformedKeysAreRecognised() {
        String key = ApiKeys.newKey("test");
        for (String bad : new String[] {null, "", key + "=", key.substring(1), "ql_prod_" + key.substring(8),
                key.replace('_', ' '), key + "\n"}) {
            assertFalse(ApiKeys.hasValidFormat(bad), String.valueOf(bad));
        }
    }

    @Test
    void hashIsSha256Hex() {
        // SHA-256 of "abc", from FIPS 180-2 appendix B.1.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApiKeys.hash("abc"));
        String key = ApiKeys.newKey("test");
        assertEquals(ApiKeys.hash(key), ApiKeys.hash(key));
        assertNotEquals(ApiKeys.hash(key), ApiKeys.hash(ApiKeys.newKey("test")));
    }

    @Test
    void hashComparison() {
        String a = ApiKeys.hash("a");
        assertTrue(ApiKeys.sameHash(a, ApiKeys.hash("a")));
        assertFalse(ApiKeys.sameHash(a, ApiKeys.hash("b")));
        assertFalse(ApiKeys.sameHash(a, a.substring(1)));
    }
}
