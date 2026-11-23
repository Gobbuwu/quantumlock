package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/** Answer to sign. */
public record SignResponse(
        String keyPairId,
        @Schema(description = "ML-DSA-65 signature (FIPS 204), 3309 bytes, Base64") String signature) {
}
