package com.quantumlock.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for API keys. Lookups for an account's own keys always include the account id. */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    List<ApiKeyEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    Optional<ApiKeyEntity> findByApiKeyIdAndAccountId(UUID apiKeyId, UUID accountId);
}
