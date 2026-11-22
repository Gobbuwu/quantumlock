package com.quantumlock.keys;

import java.util.Base64;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the API returns about a key pair. It never contains the private key, encrypted or not.
 *
 * @param keyPairId id to use in later calls
 * @param algorithm "ML-KEM-1024" or "ML-DSA-65"
 * @param status    "active", "rotated" or "revoked"
 * @param publicKey the public key, X.509 (SubjectPublicKeyInfo) encoded, then Base64
 * @param createdAt creation time, ISO-8601 in UTC
 */
public record KeyPairResponse(
        String keyPairId,
        String algorithm,
        String status,
        @Schema(description = "X.509 SubjectPublicKeyInfo, Base64 encoded") String publicKey,
        String createdAt) {

    static KeyPairResponse from(KeyPairEntity entity) {
        return new KeyPairResponse(
                entity.getKeyPairId().toString(),
                entity.getAlgorithm(),
                entity.getStatus(),
                Base64.getEncoder().encodeToString(entity.getPublicKey()),
                entity.getCreatedAt().toString());
    }
}
