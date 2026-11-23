package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/** Answer to verify. A signature that does not match is answered with valid: false, not with an error. */
public record VerifyResponse(
        String keyPairId,
        @Schema(description = "True only when the signature is valid for this message, context and key pair") boolean valid) {
}
