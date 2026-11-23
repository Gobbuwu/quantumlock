package com.quantumlock.audit;

import java.util.UUID;
import java.util.regex.Pattern;

import com.quantumlock.api.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The checked filters of an audit log request (KAN-32). Every value comes from the query string as text and is
 * checked strictly here, so anything unexpected gets the same 400 invalid_request and nothing is echoed back.
 *
 * @param keyPairId only rows about this key pair, or null for all
 * @param action    only this action ("generate", "sign" ...), or null for all
 * @param status    only "success" or only "failure", or null for both
 * @param limit     most rows in the page, 1 to {@value #MAX_LIMIT}
 * @param before    id of the last row of the previous page, or null for the newest rows
 */
public record AuditLogQuery(UUID keyPairId, String action, String status, int limit, Long before) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern LIMIT_TEXT = Pattern.compile("[1-9][0-9]{0,2}");
    private static final Pattern ID_TEXT = Pattern.compile("[1-9][0-9]{0,17}");

    /** Checks the raw query values. Null means the parameter was not given. */
    public static AuditLogQuery parse(String keyPairId, String action, String status, String limit, String before) {
        return new AuditLogQuery(
                keyPairId == null ? null : uuid(keyPairId),
                action == null ? null : action(action),
                status == null ? null : status(status),
                limit == null ? DEFAULT_LIMIT : limit(limit),
                before == null ? null : id(before));
    }

    private static UUID uuid(String text) {
        // One spelling only (lower case, with dashes), so the same id cannot be written two ways.
        if (!UUID_TEXT.matcher(text).matches()) {
            throw invalid();
        }
        return UUID.fromString(text);
    }

    private static String action(String text) {
        for (AuditAction action : AuditAction.values()) {
            if (action.value().equals(text)) {
                return text;
            }
        }
        throw invalid();
    }

    private static String status(String text) {
        if (!text.equals("success") && !text.equals("failure")) {
            throw invalid();
        }
        return text;
    }

    private static int limit(String text) {
        if (!LIMIT_TEXT.matcher(text).matches()) {
            throw invalid();
        }
        int value = Integer.parseInt(text);
        if (value > MAX_LIMIT) {
            throw invalid();
        }
        return value;
    }

    private static long id(String text) {
        if (!ID_TEXT.matcher(text).matches()) {
            throw invalid();
        }
        return Long.parseLong(text);
    }

    static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
    }
}
