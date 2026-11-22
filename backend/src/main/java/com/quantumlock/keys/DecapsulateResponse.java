package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/** Answer to decapsulate: the shared secret the ciphertext carries. */
public record DecapsulateResponse(
        String keyPairId,
        @Schema(description = "The 32-byte shared secret, Base64") String sharedSecret) {

    @Override
    public String toString() {
        return "DecapsulateResponse[keyPairId=" + keyPairId + ", sharedSecret=hidden]";
    }
}
