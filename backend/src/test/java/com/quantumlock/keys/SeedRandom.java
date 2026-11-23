package com.quantumlock.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.SecureRandom;

/** Hands out the given NIST seed bytes in order and fails if asked for more or fewer. For known-answer tests. */
final class SeedRandom extends SecureRandom {

    private final byte[] seed;
    private int position;

    SeedRandom(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        seed = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, seed, offset, part.length);
            offset += part.length;
        }
    }

    @Override
    public void nextBytes(byte[] out) {
        if (position + out.length > seed.length) {
            throw new IllegalStateException("Asked for more random bytes than the test vector provides");
        }
        System.arraycopy(seed, position, out, 0, out.length);
        position += out.length;
    }

    void assertAllUsed() {
        assertEquals(seed.length, position, "every seed byte is used exactly once");
    }
}
