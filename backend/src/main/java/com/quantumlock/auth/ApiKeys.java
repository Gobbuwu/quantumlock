package com.quantumlock.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Makes and hashes API keys (task 3.3, rules in SECURITY.md).
 *
 * <p>A key looks like {@code ql_test_} followed by 43 characters: 32 bytes from {@link SecureRandom},
 * Base64url encoded without padding. The prefix only says which environment the key is for; all the
 * strength is in the 256 random bits. The database stores the SHA-256 hash of the whole key as 64 hex
 * characters. A fast hash is enough here because the key is random, not a password a person chose.
 */
public final class ApiKeys {

    /** The request header that carries the key. Never a URL parameter. */
    public static final String HEADER = "X-API-Key";

    static final int RANDOM_BYTES = 32;

    private static final Pattern FORMAT = Pattern.compile("ql_(test|live)_[A-Za-z0-9_-]{43}");

    /** One generator for the whole application, seeded by the operating system. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    /** A new random key for the given environment ("test" or "live"). */
    static String newKey(String environment) {
        if (!"test".equals(environment) && !"live".equals(environment)) {
            throw new IllegalArgumentException("environment must be test or live");
        }
        byte[] random = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(random);
        return "ql_" + environment + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** True when the text has the shape of a key. Checked before hashing, so junk is refused cheaply. */
    static boolean hasValidFormat(String key) {
        return key != null && FORMAT.matcher(key).matches();
    }

    /** SHA-256 of the key, as 64 lower-case hex characters (the api_keys.key_hash column). */
    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }

    /** Compares two hashes in constant time, so the time taken does not tell how many characters match. */
    static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
