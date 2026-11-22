package com.quantumlock.auth;

/**
 * A key that was just created: the stored row and the key itself. The key exists only here and in the one
 * answer that shows it to its owner. It is never stored, logged or shown again.
 */
public record IssuedApiKey(ApiKeyEntity entity, String key) {

    @Override
    public String toString() {
        // Keeps the key out of log lines and debugger output that print the record.
        return "IssuedApiKey[apiKeyId=" + entity.getApiKeyId() + "]";
    }
}
