package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/v1/keys/{keyPairId}/sign. */
public record SignRequest(
        @NotBlank
        @Schema(description = "The data to sign, 1 byte to 32 KiB, standard Base64 with padding. "
                + "To sign more, sign a SHA-256 or SHA-512 digest of it.")
        String message,
        @Schema(description = "Optional FIPS 204 context string, 0 to 255 bytes, standard Base64. "
                + "Verification must use the same context.", nullable = true)
        String context) {
}
