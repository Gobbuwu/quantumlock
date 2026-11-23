package com.quantumlock.audit;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A page of audit log rows, newest first (KAN-32).
 *
 * @param entries    at most the requested limit of rows
 * @param nextBefore pass as "before" to get the next, older page; null when there are no older rows
 */
public record AuditLogPage(
        List<AuditLogEntry> entries,
        @Schema(nullable = true) String nextBefore) {
}
