package com.quantumlock.keys;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for key pairs. Every lookup the API uses includes the account id, so one team
 * can never read another team's keys by guessing an id.
 */
public interface KeyPairRepository extends JpaRepository<KeyPairEntity, UUID> {

    List<KeyPairEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    Optional<KeyPairEntity> findByKeyPairIdAndAccountId(UUID keyPairId, UUID accountId);
}
