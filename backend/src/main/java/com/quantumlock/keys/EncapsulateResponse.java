package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Answer to encapsulate. Send the ciphertext to the key pair's owner; keep the shared secret to yourself.
 */
public record EncapsulateResponse(
        String keyPairId,
        @Schema(description = "ML-KEM-1024 ciphertext, 1568 bytes, Base64") String ciphertext,
        @Schema(description = "The 32-byte shared secret, Base64. Use it as a key, for example for AES-256-GCM.")
        String sharedSecret) {

    @Override
    public String toString() {
        return "EncapsulateResponse[keyPairId=" + keyPairId + ", sharedSecret=hidden]";
    }
}
