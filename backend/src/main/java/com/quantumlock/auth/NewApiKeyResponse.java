package com.quantumlock.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to creating an API key: the only time the key itself is shown. Store it somewhere safe;
 * QuantumLock keeps only its hash and cannot show it again.
 */
public record NewApiKeyResponse(
        String apiKeyId,
        String environment,
        String createdAt,
        @Schema(description = "The new key. Shown once, send it in the X-API-Key header.") String apiKey) {

    static NewApiKeyResponse from(IssuedApiKey issued) {
        ApiKeyEntity entity = issued.entity();
        return new NewApiKeyResponse(entity.getApiKeyId().toString(), entity.getEnvironment(),
                entity.getCreatedAt().toString(), issued.key());
    }

    @Override
    public String toString() {
        return "NewApiKeyResponse[apiKeyId=" + apiKeyId + "]";
    }
}
