package com.quantumlock.client;

import java.util.List;
import java.util.Optional;

/** A page of audit log rows, newest first. */
public final class AuditLogPage {

    private final List<AuditLogEntry> entries;
    private final String nextBefore;

    AuditLogPage(List<AuditLogEntry> entries, String nextBefore) {
        this.entries = List.copyOf(entries);
        this.nextBefore = nextBefore;
    }

    /** The rows of this page, newest first. Unmodifiable. */
    public List<AuditLogEntry> entries() {
        return entries;
    }

    /** Pass to {@link AuditLogQuery#before(String)} for the next, older page. Empty on the last page. */
    public Optional<String> nextBefore() {
        return Optional.ofNullable(nextBefore);
    }

    @Override
    public String toString() {
        return "AuditLogPage[" + entries.size() + " entries, nextBefore=" + nextBefore + "]";
    }
}
