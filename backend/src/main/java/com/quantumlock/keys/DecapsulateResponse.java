package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/** Answer to decapsulate: the shared secret the ciphertext carries. */
public record DecapsulateResponse(
        String keyPairId,
        @Schema(description = "The 32-byte FIPS 203 ML-KEM shared secret K (no extra key derivation), Base64. "
                + "Matches what the sender's FIPS 203 encapsulation produced.") String sharedSecret) {

    @Override
    public String toString() {
        return "DecapsulateResponse[keyPairId=" + keyPairId + ", sharedSecret=hidden]";
    }
}
