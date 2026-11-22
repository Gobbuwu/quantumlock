package com.quantumlock.auth;

/**
 * What the API returns about an existing API key. Never the key and never its hash.
 *
 * @param apiKeyId    id to use for revoking
 * @param environment "test" or "live"
 * @param createdAt   creation time, ISO-8601 in UTC
 * @param revoked     true once revoked; a revoked key no longer works
 */
public record ApiKeyResponse(String apiKeyId, String environment, String createdAt, boolean revoked) {

    static ApiKeyResponse from(ApiKeyEntity entity) {
        return new ApiKeyResponse(entity.getApiKeyId().toString(), entity.getEnvironment(),
                entity.getCreatedAt().toString(), entity.isRevoked());
    }
}
