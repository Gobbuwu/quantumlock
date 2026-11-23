package com.quantumlock.keys;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the API returns about a key pair. It never contains the private key, encrypted or not.
 *
 * @param keyPairId id to use in later calls
 * @param algorithm "ML-KEM-1024" or "ML-DSA-65"
 * @param status    "active", "rotated" or "revoked"
 * @param publicKey the public key, X.509 (SubjectPublicKeyInfo) encoded, then Base64
 * @param createdAt creation time, ISO-8601 in UTC
 * @param rotatedAt when the key pair was rotated, or null
 * @param gracePeriodEndsAt until when a rotated key pair can still decapsulate and verify, or null
 * @param replacedBy id of the key pair that replaced this one, or null
 */
public record KeyPairResponse(
        String keyPairId,
        String algorithm,
        String status,
        @Schema(description = "X.509 SubjectPublicKeyInfo, Base64 encoded") String publicKey,
        String createdAt,
        @Schema(nullable = true) String rotatedAt,
        @Schema(nullable = true) String gracePeriodEndsAt,
        @Schema(nullable = true) String replacedBy) {

    static KeyPairResponse from(KeyPairEntity entity) {
        return new KeyPairResponse(
                entity.getKeyPairId().toString(),
                entity.getAlgorithm(),
                entity.getStatus(),
                Base64.getEncoder().encodeToString(entity.getPublicKey()),
                entity.getCreatedAt().toString(),
                text(entity.getRotatedAt()),
                text(entity.getGracePeriodEndsAt()),
                text(entity.getReplacedBy()));
    }

    private static String text(Instant time) {
        return time == null ? null : time.toString();
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
