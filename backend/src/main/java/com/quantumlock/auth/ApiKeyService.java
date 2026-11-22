package com.quantumlock.auth;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.quantumlock.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues, checks and revokes API keys (task 3.3). */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeys;
    private final Clock clock = Clock.systemUTC();

    public ApiKeyService(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** Creates a key for the account. Only its hash is stored; the key is returned once, here. */
    @Transactional
    public IssuedApiKey issue(UUID accountId, String environment) {
        String key = ApiKeys.newKey(environment);
        ApiKeyEntity entity = new ApiKeyEntity(UUID.randomUUID(), accountId, ApiKeys.hash(key), environment,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return new IssuedApiKey(apiKeys.save(entity), key);
    }

    /**
     * Finds who a presented key belongs to. Empty for every kind of failure (missing, malformed, unknown,
     * revoked), so the caller can give the same answer for all of them.
     */
    @Transactional(readOnly = true)
    public Optional<Caller> authenticate(String presentedKey) {
        if (!ApiKeys.hasValidFormat(presentedKey)) {
            return Optional.empty();
        }
        String hash = ApiKeys.hash(presentedKey);
        return apiKeys.findByKeyHash(hash)
                .filter(stored -> ApiKeys.sameHash(hash, stored.getKeyHash()))
                .filter(stored -> !stored.isRevoked())
                .map(stored -> new Caller(stored.getAccountId(), stored.getApiKeyId()));
    }

    @Transactional(readOnly = true)
    public List<ApiKeyEntity> list(UUID accountId) {
        return apiKeys.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    /**
     * Revokes one of the account's keys. It stops working on the next request. Revoking twice is allowed.
     * A key of another account is reported exactly like a key that does not exist.
     */
    @Transactional
    public void revoke(UUID accountId, UUID apiKeyId) {
        ApiKeyEntity key = apiKeys.findByApiKeyIdAndAccountId(apiKeyId, accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api_key_not_found"));
        key.revoke();
        apiKeys.save(key);
    }
}
