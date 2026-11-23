package com.quantumlock.client;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What to read from the audit log: filters, page size and where to start. Immutable: every method returns a
 * new query. Start with {@link #newest()}, and pass a page's {@link AuditLogPage#nextBefore()} to
 * {@link #before(String)} for the next, older page.
 */
public final class AuditLogQuery {

    /** Rows per page when no limit is set. */
    public static final int DEFAULT_LIMIT = 50;
    /** Most rows the API returns in one page. */
    public static final int MAX_LIMIT = 100;

    static final Pattern ACTION = Pattern.compile("[a-z]{1,32}");
    static final Pattern CURSOR = Pattern.compile("[1-9][0-9]{0,17}");

    private final UUID keyPairId;
    private final String action;
    private final Boolean success;
    private final int limit;
    private final String before;

    private AuditLogQuery(UUID keyPairId, String action, Boolean success, int limit, String before) {
        this.keyPairId = keyPairId;
        this.action = action;
        this.success = success;
        this.limit = limit;
        this.before = before;
    }

    /** The newest {@value #DEFAULT_LIMIT} rows, unfiltered. */
    public static AuditLogQuery newest() {
        return new AuditLogQuery(null, null, null, DEFAULT_LIMIT, null);
    }

    /** Only rows about this key pair. */
    public AuditLogQuery keyPair(UUID keyPairId) {
        return new AuditLogQuery(Objects.requireNonNull(keyPairId, "keyPairId"), action, success, limit, before);
    }

    /** Only this action: "generate", "encapsulate", "decapsulate", "sign", "verify", "rotate" or "revoke". */
    public AuditLogQuery action(String action) {
        Objects.requireNonNull(action, "action");
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("action must be a lower-case word such as \"sign\"");
        }
        return new AuditLogQuery(keyPairId, action, success, limit, before);
    }

    /** Only successful rows (true) or only failed ones (false). */
    public AuditLogQuery success(boolean success) {
        return new AuditLogQuery(keyPairId, action, success, limit, before);
    }

    /** Rows per page, 1 to {@value #MAX_LIMIT}. */
    public AuditLogQuery limit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
        return new AuditLogQuery(keyPairId, action, success, limit, before);
    }

    /** Start after this row: the {@link AuditLogPage#nextBefore()} of the previous page. */
    public AuditLogQuery before(String cursor) {
        Objects.requireNonNull(cursor, "cursor");
        if (!CURSOR.matcher(cursor).matches()) {
            throw new IllegalArgumentException("cursor must be a nextBefore value from a previous page");
        }
        return new AuditLogQuery(keyPairId, action, success, limit, cursor);
    }

    public Optional<UUID> keyPairId() {
        return Optional.ofNullable(keyPairId);
    }

    public Optional<String> action() {
        return Optional.ofNullable(action);
    }

    public Optional<Boolean> success() {
        return Optional.ofNullable(success);
    }

    public int limit() {
        return limit;
    }

    public Optional<String> before() {
        return Optional.ofNullable(before);
    }

    /** The query string. Every value was checked above, so none needs escaping. */
    String toQueryString() {
        StringBuilder query = new StringBuilder("limit=").append(limit);
        if (keyPairId != null) {
            query.append("&keyPairId=").append(keyPairId);
        }
        if (action != null) {
            query.append("&action=").append(action);
        }
        if (success != null) {
            query.append("&status=").append(success ? "success" : "failure");
        }
        if (before != null) {
            query.append("&before=").append(before);
        }
        return query.toString();
    }

    @Override
    public String toString() {
        return "AuditLogQuery[" + toQueryString() + "]";
    }
}
