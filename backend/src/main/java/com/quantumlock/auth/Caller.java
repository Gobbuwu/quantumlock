package com.quantumlock.auth;

import java.util.UUID;

/**
 * Who made the current request: the account of the API key that was presented, and that key's id.
 * Only {@link com.quantumlock.security.ApiKeyAuthFilter} creates one, after checking the key.
 */
public record Caller(UUID accountId, UUID apiKeyId) {
}
