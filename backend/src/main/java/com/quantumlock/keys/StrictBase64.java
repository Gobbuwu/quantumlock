package com.quantumlock.keys;

import java.util.Base64;

/**
 * Strict Base64 for binary request fields: standard alphabet, with padding, no spaces or line breaks, and only one
 * accepted spelling of each value (re-encoding must give the same text). The length is checked before decoding.
 */
final class StrictBase64 {

    private StrictBase64() {
    }

    /** The decoded bytes, or null when the text is not strict Base64 of minBytes to maxBytes bytes. */
    static byte[] decode(String text, int minBytes, int maxBytes) {
        if (text == null || text.length() < encodedLength(minBytes) || text.length() > encodedLength(maxBytes)) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length < minBytes || decoded.length > maxBytes
                || !Base64.getEncoder().encodeToString(decoded).equals(text)) {
            return null;
        }
        return decoded;
    }

    private static int encodedLength(int bytes) {
        return (bytes + 2) / 3 * 4;
    }
}
