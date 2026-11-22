package com.quantumlock.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<AuditLogEntity> findByKeyPairId(UUID keyPairId);
}
