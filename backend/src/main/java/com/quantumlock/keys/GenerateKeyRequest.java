package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/v1/keys/generate. */
public record GenerateKeyRequest(
        @NotBlank
        @Schema(description = "Algorithm of the new key pair", allowableValues = {"ML-KEM-1024", "ML-DSA-65"},
                example = "ML-KEM-1024")
        String algorithm) {
}
