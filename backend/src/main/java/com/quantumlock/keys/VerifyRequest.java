package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/v1/keys/{keyPairId}/verify. */
public record VerifyRequest(
        @NotBlank
        @Schema(description = "The signed data, 1 byte to 32 KiB, standard Base64 with padding") String message,
        @NotBlank
        @Schema(description = "ML-DSA-65 signature, 3309 bytes, standard Base64 with padding") String signature,
        @Schema(description = "The context string used when signing, if any, standard Base64", nullable = true)
        String context) {
}
