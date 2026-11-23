package com.quantumlock.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a team's audit log, newest first, one page at a time (KAN-32).
 *
 * <p>Pages are cut by keyset (time, then id), not by offset, so rows written while someone pages through the
 * log never make a row appear twice or go missing. The order uses the (account_id, created_at) index. Every query
 * is limited to the caller's account, so another team's rows, key pairs and cursors behave as if they did not exist.
 */
@Service
public class AuditLogService {

    private final EntityManager entityManager;

    public AuditLogService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public AuditLogPage page(UUID accountId, AuditLogQuery query) {
        Instant cursorTime = query.before() == null ? null : cursorTime(accountId, query.before());

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditLogEntity> select = cb.createQuery(AuditLogEntity.class);
        Root<AuditLogEntity> row = select.from(AuditLogEntity.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(row.get("accountId"), accountId));
        if (query.keyPairId() != null) {
            where.add(cb.equal(row.get("keyPairId"), query.keyPairId()));
        }
        if (query.action() != null) {
            where.add(cb.equal(row.get("action"), query.action()));
        }
        if (query.status() != null) {
            where.add(cb.equal(row.get("status"), query.status()));
        }
        if (cursorTime != null) {
            // The first condition is implied by the second; it lets the index skip the newer rows directly.
            where.add(cb.lessThanOrEqualTo(row.<Instant>get("createdAt"), cursorTime));
            where.add(cb.or(
                    cb.lessThan(row.<Instant>get("createdAt"), cursorTime),
                    cb.and(cb.equal(row.get("createdAt"), cursorTime),
                            cb.lessThan(row.<Long>get("logId"), query.before()))));
        }
        select.where(where.toArray(Predicate[]::new))
                .orderBy(cb.desc(row.get("createdAt")), cb.desc(row.get("logId")));

        // One extra row tells whether an older page exists, without a count query.
        List<AuditLogEntity> rows = entityManager.createQuery(select)
                .setMaxResults(query.limit() + 1)
                .getResultList();
        boolean more = rows.size() > query.limit();
        List<AuditLogEntry> entries = rows.stream().limit(query.limit()).map(AuditLogEntry::from).toList();
        String nextBefore = more ? entries.get(entries.size() - 1).id() : null;
        return new AuditLogPage(entries, nextBefore);
    }

    /** The time of the cursor row. A row of another team, or no row at all, is the same invalid request. */
    private Instant cursorTime(UUID accountId, long logId) {
        List<Instant> times = entityManager.createQuery(
                        "SELECT a.createdAt FROM AuditLogEntity a WHERE a.logId = :logId AND a.accountId = :accountId",
                        Instant.class)
                .setParameter("logId", logId)
                .setParameter("accountId", accountId)
                .getResultList();
        if (times.isEmpty()) {
            throw AuditLogQuery.invalid();
        }
        return times.get(0);
    }
}
