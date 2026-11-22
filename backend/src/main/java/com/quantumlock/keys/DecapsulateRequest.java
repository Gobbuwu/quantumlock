package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/v1/keys/{keyPairId}/decapsulate. */
public record DecapsulateRequest(
        @NotBlank
        @Schema(description = "ML-KEM-1024 ciphertext, 1568 bytes, standard Base64 with padding")
        String ciphertext) {
}
