package com.quantumlock.keys;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Database access for key pairs. Every lookup the API uses includes the account id, so one team
 * can never read another team's keys by guessing an id.
 */
public interface KeyPairRepository extends JpaRepository<KeyPairEntity, UUID> {

    List<KeyPairEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    Optional<KeyPairEntity> findByKeyPairIdAndAccountId(UUID keyPairId, UUID accountId);

    /**
     * The same lookup with the row locked (SELECT ... FOR UPDATE) until the transaction ends, so two status
     * changes of one key pair (two rotations, or a rotation and a revocation) run one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KeyPairEntity k WHERE k.keyPairId = :keyPairId AND k.accountId = :accountId")
    Optional<KeyPairEntity> findForUpdate(@Param("keyPairId") UUID keyPairId, @Param("accountId") UUID accountId);
}
